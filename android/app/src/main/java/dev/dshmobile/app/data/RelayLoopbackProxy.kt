package dev.dshmobile.app.data

import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.framing.PongFrame
import org.java_websocket.handshake.ServerHandshake
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters

/** Private app-only CONNECT endpoint. Ordinary inner TLS owns all host authentication. */
internal class RelayLoopbackProxy(
    private val endpoint: HostEndpoint, owner: TransportIoOwner? = null, private val onOverflow: (() -> Unit)? = null,
) : AutoCloseable {
    private val io = owner ?: TransportIoOwner()
    private val ownsIo = owner == null
    private val relay = requireNotNull(endpoint.relay)
    private val host = endpoint.baseUrl.toHttpUrl().host
    private val authority = "$host:443"
    private val random = SecureRandom()
    private val credential = "DSH " + ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val closed = AtomicBoolean()
    private val streams = ConcurrentHashMap.newKeySet<Tunnel>()
    private val admission = Any()
    private val threads = ThreadFactory { task -> Thread(task, "dsh-relay-io").apply { isDaemon = true } }
    private val workers = ThreadPoolExecutor(0, 16, 20, TimeUnit.SECONDS, SynchronousQueue(), threads)
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { task -> Thread(task, "dsh-relay-watchdog").apply { isDaemon = true } }).apply {
        removeOnCancelPolicy = true
    }
    private val listener = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 8) }
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", listener.localPort))
    val authenticator = Authenticator { route, response ->
        if (route?.proxy != proxy || response.request.method != "CONNECT" || response.request.url.host != host || response.request.url.port != 443 ||
            response.request.header("Proxy-Authorization") != null || response.challenges().none { it.scheme.equals("OkHttp-Preemptive", true) }) null
        else response.request.newBuilder().header("Proxy-Authorization", credential).build()
    }

    init {
        timer.scheduleAtFixedRate({ streams.forEach { it.tick() } }, 100, 100, TimeUnit.MILLISECONDS)
        Thread({ accept() }, "dsh-relay-accept").apply { isDaemon = true; start() }
    }
    private fun accept() {
        while (!closed.get()) {
            val socket = try { listener.accept() } catch (_: IOException) { return }
            val tunnel = Tunnel(socket)
            val allowed = synchronized(admission) { !closed.get() && streams.size < 8 && streams.add(tunnel) }
            if (!allowed) { socket.close(); continue }
            try { workers.execute { tunnel.run() } } catch (_: Exception) { tunnel.close() }
        }
    }
    internal fun retire() {
        if (!closed.compareAndSet(false, true)) return
        val retiring = synchronized(admission) { streams.toArray().filterIsInstance<Tunnel>() }
        retiring.forEach { it.retire() }
        timer.shutdownNow()
    }
    internal fun closePhysical() {
        runCatching { listener.close() }
        val closing = synchronized(admission) { streams.toArray().filterIsInstance<Tunnel>() }
        closing.forEach { it.closePhysical() }
        workers.shutdownNow()
    }
    override fun close() {
        retire()
        if (ownsIo) io.close { closePhysical() }
        else if (!io.submit { closePhysical() }) onOverflow?.invoke()
    }
    suspend fun awaitClosed() = io.awaitClosed()

    private inner class Tunnel(private val local: Socket) {
        private val terminal = AtomicBoolean()
        private val openedAt = System.nanoTime()
        private val ready = CountDownLatch(1)
        @Volatile private var prepared = false
        @Volatile private var ws: WebSocketClient? = null
        private var rawOuter: Socket? = null
        private var preparedOuter: Socket? = null
        private val incoming = RelayIncoming()
        private val writes = Object()
        private var barrier: ByteArray? = null
        private var barrierAt = 0L
        private var uncoveredAt = 0L
        private var idlePingAt = openedAt
        private var controlWindowAt = openedAt
        private var controlCount = 0

        fun run() {
            var pumping = false
            try {
                local.soTimeout = 10_000
                local.tcpNoDelay = true
                if (!readConnect()) return
                RelayPolicy.requireCurrent(relay)
                val headers = mapOf("Authorization" to "Bearer ${relay.accessToken}", "X-DSH-Route" to relay.routeId, "X-DSH-Access" to relay.accessId)
                val url = URI(relay.url + "/v1/mobile")
                val port = if (url.port >= 0) url.port else if (url.scheme == "wss") 443 else 80
                val raw = Socket(Proxy.NO_PROXY)
                synchronized(writes) {
                    if (terminal.get()) { raw.close(); return }
                    rawOuter = raw // Owned before DNS/connect; cleanup can abort it at every startup phase.
                }
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(url.host, port), 10_000)
                if (terminal.get()) return
                val network = if (url.scheme == "wss") {
                    (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                        .createSocket(raw, url.host, port, true)
                } else raw
                synchronized(writes) {
                    if (terminal.get()) { network.close(); return }
                    preparedOuter = network
                }
                val outer = object : WebSocketClient(url, RelayWebSocketDraft(), headers, 10_000) {
                    override fun run() {
                        try { super.run() }
                        finally {
                            runCatching { raw.close() }
                            runCatching { network.close() }
                            closeConnection(1006, "relay closed") // Writer exists or startup failed; no later socket creation.
                        }
                    }
                    override fun onSetSSLParameters(parameters: SSLParameters) {
                        super.onSetSSLParameters(parameters)
                        parameters.serverNames = listOf(SNIHostName(url.host))
                        parameters.protocols = parameters.protocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                        if (parameters.protocols.isEmpty()) throw IOException("relay TLS unavailable")
                    }
                    override fun onOpen(handshake: ServerHandshake) { }
                    override fun onMessage(message: String) { this@Tunnel.close() }
                    override fun onMessage(bytes: ByteBuffer) {
                        try {
                            if (!prepared) {
                                if (bytes.remaining() > 4096) throw IOException("invalid ready")
                                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes.duplicate()).toString()
                                val message = mobileJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: throw IOException("invalid ready")
                                if (message.keys != setOf("type", "version") || message["type"] != kotlinx.serialization.json.JsonPrimitive("ready") ||
                                    message["version"] != kotlinx.serialization.json.JsonPrimitive(1)) throw IOException("invalid ready")
                                prepared = true
                                ready.countDown()
                            } else {
                                val chunk = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
                                incoming.offer(chunk)
                            }
                        } catch (_: Exception) { this@Tunnel.close() }
                    }
                    override fun onClosing(code: Int, reason: String, remote: Boolean) { outerEnded(code, remote) }
                    override fun onClose(code: Int, reason: String, remote: Boolean) { outerEnded(code, remote) }
                    override fun onError(exception: Exception) { this@Tunnel.close() }
                    override fun onWebsocketPing(connection: WebSocket, frame: Framedata) {
                        synchronized(writes) {
                            if (!controlAllowed() || queueCount() >= 8) { this@Tunnel.close(); return }
                            connection.sendFrame(PongFrame(frame as PingFrame))
                        }
                    }
                    override fun onWebsocketPong(connection: WebSocket, frame: Framedata) {
                        synchronized(writes) {
                            if (!controlAllowed()) { this@Tunnel.close(); return }
                            val pending = barrier ?: return
                            if (frame.payloadData.remaining() == pending.size && frame.payloadData.duplicate().let { buffer -> pending.all { buffer.get() == it } }) {
                                barrier = null
                                barrierAt = 0
                                maybePing(System.nanoTime())
                            }
                        }
                    }
                }.apply {
                    setProxy(Proxy.NO_PROXY)
                    @Suppress("DEPRECATION")
                    setSocket(network) // Real socket; WSS is already default-CA TLS with relay hostname.
                    setConnectionLostTimeout(0) // Every ping and automatic pong goes through our queue gate.
                    setReceiveBufferSize(8192) // Bound the library's pre-guard handshake growth and decode batch.
                    setTcpNoDelay(true)
                    setDaemon(true)
                }
                synchronized(writes) {
                    if (terminal.get()) return
                    ws = outer
                    outer.connect()
                }
                val remaining = STALL_NANOS - (System.nanoTime() - openedAt)
                if (remaining <= 0 || !ready.await(remaining, TimeUnit.NANOSECONDS) || terminal.get() || !prepared) return
                local.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                local.getOutputStream().flush()
                local.soTimeout = 0
                workers.execute { pumpIncoming() }
                pumping = true
                val bytes = ByteArray(RelayWebSocketDraft.MAX_FRAME)
                while (!terminal.get()) {
                    val count = local.getInputStream().read(bytes)
                    if (count < 0) break
                    if (count == 0) continue
                    send(bytes.copyOf(count))
                }
            } catch (_: Exception) { /* Only a safe network failure reaches the inner API. */ }
            finally {
                // After outer EOF the incoming pump owns final close, with the same stall watchdog.
                // Upload failure must not overtake a response already accepted from the peer.
                if (!pumping || !incoming.isFinished) close()
            }
        }
        private fun readConnect(): Boolean {
            val data = java.io.ByteArrayOutputStream()
            var tail = 0
            while (data.size() < RelayWebSocketDraft.MAX_HEADERS) {
                val byte = local.getInputStream().read()
                if (byte < 0) return false
                if (byte !in 9..13 && byte !in 32..126) return false
                data.write(byte)
                tail = (tail shl 8) or byte
                if (tail == 0x0d0a0d0a) break
            }
            if (tail != 0x0d0a0d0a) return false
            val lines = data.toString("US-ASCII").removeSuffix("\r\n\r\n").split("\r\n")
            if (lines.firstOrNull() != "CONNECT $authority HTTP/1.1") return false
            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon < 1 || line.substring(0, colon).any { !it.isLetterOrDigit() && it != '-' } || line.startsWith(' ') || line.startsWith('\t')) return false
                val key = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
                if (headers.put(key, line.substring(colon + 1).trim()) != null) return false
            }
            if (headers.keys.any { it !in setOf("host", "proxy-authorization", "proxy-connection", "user-agent") } || headers["host"] != authority ||
                headers["proxy-authorization"]?.toByteArray()?.let { java.security.MessageDigest.isEqual(it, credential.toByteArray()) } != true) return false
            return true
        }
        private fun outerEnded(code: Int, remote: Boolean) {
            // Ordered peer EOF (including raw outer TCP EOF) must not erase accepted TLS bytes.
            // Errors/protocol rejection and explicit retirement still abort without draining.
            if (prepared && remote && code in setOf(1000, 1001, 1005, 1006)) incoming.finish()
            else close()
        }
        private fun pumpIncoming() {
            try {
                incoming.drainTo(local.getOutputStream())
            } catch (_: Exception) { /* Abort/timeout or local socket failure: no replay. */ }
            finally { close() }
        }
        private fun queueCount(outer: WebSocketClient? = ws): Int = (outer?.connection as? WebSocketImpl)?.outQueue?.size ?: 8
        private fun send(bytes: ByteArray) {
            synchronized(writes) {
                val until = System.nanoTime() + STALL_NANOS
                while (!terminal.get() && queueCount() >= 8) {
                    if (System.nanoTime() >= until) throw IOException("relay stalled")
                    writes.wait(25)
                }
                if (terminal.get() || incoming.isFinished) throw IOException("relay closed")
                val outer = ws ?: throw IOException("relay closed")
                outer.send(bytes)
                if (uncoveredAt == 0L) uncoveredAt = System.nanoTime()
                maybePing(System.nanoTime())
            }
        }
        private fun maybePing(now: Long) {
            val outer = ws ?: return
            val idle = idlePingAt
            val uncovered = uncoveredAt
            if (terminal.get() || incoming.isFinished || !outer.isOpen || barrier != null || queueCount(outer) >= 8 ||
                now - idle < TimeUnit.SECONDS.toNanos(1) ||
                (uncovered == 0L && now - idle < TimeUnit.SECONDS.toNanos(15))) return
            val nonce = ByteArray(16).also(random::nextBytes)
            val ping = PingFrame().apply { setPayload(ByteBuffer.wrap(nonce)) }
            barrier = nonce
            barrierAt = if (uncovered != 0L) uncovered else now
            uncoveredAt = 0
            idlePingAt = now
            outer.sendFrame(ping)
        }
        private fun controlAllowed(): Boolean {
            val now = System.nanoTime()
            if (now - controlWindowAt > TimeUnit.SECONDS.toNanos(1)) { controlCount = 0; controlWindowAt = now }
            return ++controlCount <= 8
        }
        fun tick() {
            if (terminal.get()) return
            val now = System.nanoTime()
            if (relay.expiresAt <= System.currentTimeMillis() || (!prepared && now - openedAt >= STALL_NANOS) ||
                incoming.isStalled(now)) { close(); return }
            synchronized(writes) {
                val barrierStarted = barrierAt
                val uncoveredStarted = uncoveredAt
                if (!incoming.isFinished && ((barrierStarted != 0L && now - barrierStarted >= STALL_NANOS) ||
                    (uncoveredStarted != 0L && now - uncoveredStarted >= STALL_NANOS))) { close(); return }
                runCatching { maybePing(now) }.onFailure { close() }
                writes.notifyAll()
            }
        }
        fun retire(): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            ready.countDown()
            // Logical retirement must not acquire the writes lock (control callbacks may retire us).
            incoming.abort()
            return true
        }
        fun close() {
            if (!retire()) return
            if (!io.submit { closePhysical() }) {
                // Do not drop physical cleanup on overflow. The reserved terminal task owns all streams.
                this@RelayLoopbackProxy.close()
            }
        }
        fun closePhysical() {
            runCatching { local.close() }
            val (raw, network, outer) = synchronized(writes) { Triple(rawOuter, preparedOuter, ws) }
            runCatching { raw?.close() } // Abort TCP first: no TLS close-notify on a live/stalled peer.
            runCatching { network?.close() }
            // Startup on the already-closed socket fails; its run finalizer closes the engine/writer.
            synchronized(writes) { (outer?.connection as? WebSocketImpl)?.outQueue?.clear(); writes.notifyAll() }
            incoming.abort()
            streams.remove(this)
        }
    }
    private companion object { val STALL_NANOS = TimeUnit.SECONDS.toNanos(10) }
}
