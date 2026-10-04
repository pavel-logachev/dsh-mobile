package dev.dshmobile.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real H1 TLS + opaque relay: EOF handshake and actual GET retry are observed, never inferred from sleeps. */
class RelayIdleReadTest {
    private val route = "0123456789abcdef0123456789abcdef"
    private val logical = "h-$route.dsh.invalid"
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
    private val snapshot = """{"session":{"id":"synthetic-session","title":"Synthetic","workspaceId":"synthetic-workspace","updatedAt":1,"running":false,"canExecute":true},"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}"""
    private val capabilities = """{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":false,"liveSnapshots":true,"attachments":false,"questions":false,"approvals":false,"push":false}}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `first history read after backend idle EOF recovers without requiring a mutation`(): Unit = runBlocking {
        withFixture { host, bridge, endpoint ->
            val retries = requireNotNull(observedRetries)
            val api = HostApi(endpoint, "synthetic-only-device-bearer")
            try {
                host.enqueue(json(capabilities).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
                host.enqueue(json(snapshot))
                assertEquals("Synthetic", runBlocking { api.capabilities() }.hostName)
                assertTrue("Fixture observed backend EOF", bridge.backendEof.await(3, TimeUnit.SECONDS))
                assertTrue("Client acknowledged the ordered outer EOF while the TLS connection was pooled", bridge.peerClosed.await(3, TimeUnit.SECONDS))
                assertEquals("Catalogue needed no recovery", 0, retries.get())
                assertEquals(1, bridge.opened.get())
                assertEquals("synthetic-session", runBlocking { api.snapshot("synthetic-session") }.session.id)
                assertEquals("Only one catalogue and one canonical snapshot reach the host", 2, host.requestCount)
                assertEquals("Recovery opens a fresh relay stream", 2, bridge.opened.get())
                assertEquals("The first snapshot really entered the one-shot GET recovery branch", 1, retries.get())
            } finally { runBlocking { api.closeAndAwait() } }
        }
    }

    @Test fun `two header EOFs exhaust one read recovery without a third dispatch`(): Unit = runBlocking {
        withFixture { host, bridge, endpoint ->
            val api = HostApi(endpoint, "synthetic-only-device-bearer")
            try {
                repeat(2) { host.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
                host.enqueue(json(snapshot))
                try { runBlocking { api.snapshot("synthetic-session") }; fail("Two lost read responses must stop") }
                catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
                assertEquals("At most two physical GET calls", 2, host.requestCount)
                assertEquals("Exactly one recovery, not an implicit fresh initial connection", 1, observedRetries!!.get())
                assertEquals(2, bridge.opened.get())
                repeat(2) { assertEquals("GET", host.takeRequest(2, TimeUnit.SECONDS)!!.method) }
            } finally { runBlocking { api.closeAndAwait() } }
        }
    }

    @Test fun `complete mutation response survives backend EOF without replay`(): Unit = runBlocking {
        withFixture { host, bridge, endpoint ->
            val api = HostApi(endpoint, "synthetic-only-device-bearer")
            try {
                val id = "f072cf3d-40e0-43e6-93c4-b27bdb3b8877"
                host.enqueue(json("""{"requestId":"$id","status":"accepted","updatedAt":1}""").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
                val command = StoredCommand(id, "send", "synthetic-session", "Synthetic exact message")
                assertEquals("accepted", runBlocking { api.command(command) }.status)
                assertTrue("Fixture observed backend EOF", bridge.backendEof.await(3, TimeUnit.SECONDS))
                assertEquals("No replay is needed to drain a complete response", 1, host.requestCount)
                assertEquals("Mutations never enter read recovery", 0, observedRetries!!.get())
                assertEquals(1, bridge.opened.get())
                assertEquals("POST", host.takeRequest(2, TimeUnit.SECONDS)!!.method)
            } finally { runBlocking { api.closeAndAwait() } }
        }
    }

    @Test fun `partial history body through relay fails without replay`(): Unit = runBlocking {
        withFixture { host, bridge, endpoint ->
            val api = HostApi(endpoint, "synthetic-only-device-bearer")
            try {
                host.enqueue(json(snapshot).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                host.enqueue(json(snapshot))
                try { runBlocking { api.snapshot("synthetic-session") }; fail("A partial body is not a recoverable pre-response failure") }
                catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
                assertEquals("Body failures never enter GET recovery", 1, host.requestCount)
                assertEquals(0, observedRetries!!.get())
                assertEquals(1, bridge.opened.get())
                assertEquals("GET", host.takeRequest(2, TimeUnit.SECONDS)!!.method)
            } finally { runBlocking { api.closeAndAwait() } }
        }
    }

    @Test fun `lost mutation response through relay is not replayed`(): Unit = runBlocking {
        withFixture { host, bridge, endpoint ->
            val api = HostApi(endpoint, "synthetic-only-device-bearer")
            try {
                host.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                host.enqueue(json("""{"requestId":"f072cf3d-40e0-43e6-93c4-b27bdb3b8877","status":"accepted","updatedAt":1}"""))
                val command = StoredCommand("f072cf3d-40e0-43e6-93c4-b27bdb3b8877", "send", "synthetic-session", "Synthetic exact message")
                try { runBlocking { api.command(command) }; fail("Lost response must remain uncertain") }
                catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
                assertEquals(1, host.requestCount)
                assertEquals("Uncertain POST is never retried", 0, observedRetries!!.get())
                assertEquals(1, bridge.opened.get())
                assertEquals("POST", host.takeRequest(2, TimeUnit.SECONDS)!!.method)
            } finally { runBlocking { api.closeAndAwait() } }
        }
    }

    private var observedRetries: AtomicInteger? = null
    private fun withFixture(block: (MockWebServer, EofBridge, HostEndpoint) -> Unit) {
        val retries = AtomicInteger()
        observedRetries = retries
        ReadRetryObservation.onRetry = { retries.incrementAndGet() }
        try { withTransportFixture(block) }
        finally { ReadRetryObservation.onRetry = null; observedRetries = null }
    }

    private fun withTransportFixture(block: (MockWebServer, EofBridge, HostEndpoint) -> Unit) {
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { host ->
            host.protocols = listOf(Protocol.HTTP_1_1)
            host.useHttps(tls.sslSocketFactory(), false)
            host.start()
            val bridge = EofBridge(host.port)
            bridge.start()
            try {
                assertTrue(bridge.started.await(3, TimeUnit.SECONDS))
                val endpoint = HostEndpoint("https://$logical", CertificatePinner.pin(certificate.certificate), certificate.certificatePem(),
                    RelaySettings("ws://127.0.0.1:${bridge.port}", route, "a172cf3d-40e0-43e6-93c4-b27bdb3b8877",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", System.currentTimeMillis() + 600_000))
                block(host, bridge, endpoint)
            } finally { bridge.connections.toList().forEach { it.closeConnection(1000, "fixture done") }; bridge.stop(1000) }
        }
    }
    private class EofBridge(private val target: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", 0), 1, listOf(RelayWebSocketDraft())) {
        val started = CountDownLatch(1)
        val backendEof = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val opened = AtomicInteger()
        private val pipes = ConcurrentHashMap<WebSocket, Socket>()
        override fun onStart() { started.countDown() }
        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            opened.incrementAndGet()
            val socket = Socket("127.0.0.1", target)
            pipes[connection] = socket
            connection.send("{\"type\":\"ready\",\"version\":1}".toByteArray())
            Thread {
                try {
                    val bytes = ByteArray(16 * 1024)
                    while (connection.isOpen) {
                        val count = socket.getInputStream().read(bytes)
                        if (count < 0) break
                        connection.send(bytes.copyOf(count))
                    }
                } catch (_: java.io.IOException) { }
                finally {
                    // closeConnection aborts the library outQueue; close orders EOF after queued TLS.
                    connection.close(1000, "fixture EOF")
                    socket.close()
                    backendEof.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        override fun onMessage(connection: WebSocket, message: String) { connection.closeConnection(1002, "binary only") }
        override fun onMessage(connection: WebSocket, bytes: ByteBuffer) {
            try { pipes[connection]?.getOutputStream()?.apply { write(ByteArray(bytes.remaining()).also { bytes.get(it) }); flush() } }
            catch (_: java.io.IOException) { connection.closeConnection(1000, "fixture EOF") }
        }
        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
            pipes.remove(connection)?.close()
            peerClosed.countDown() // Close handshake completed, not merely queued behind response TLS bytes.
        }
        override fun onError(connection: WebSocket?, exception: Exception) { connection?.closeConnection(1006, "fixture error") }
    }
}
