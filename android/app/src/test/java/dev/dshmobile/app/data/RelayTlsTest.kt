package dev.dshmobile.app.data

import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Actual loopback sockets and inner HTTPS. Outer WS is an explicit debug-only local fixture. */
class RelayTlsTest {
    private val route = "0123456789abcdef0123456789abcdef"
    private val logical = "h-$route.dsh.invalid"
    private val bootstrapId = "a172cf3d-40e0-43e6-93c4-b27bdb3b8877"
    private val bootstrapToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test fun `logical pinned HTTPS crosses an opaque relay and large response stays bounded`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        withBridge(certificate) { host, bridge ->
            val endpoint = endpoint(certificate, bridge)
            RelayLoopbackProxy(endpoint).use { proxy ->
                val client = SecureTransport.client(endpoint, proxy)
                try {
                    val payload = "synthetic:" + "z".repeat(2 * 1024 * 1024 - 32)
                    host.enqueue(MockResponse().setBody(payload))
                    client.newCall(Request.Builder().url(endpoint.baseUrl + "/v1/capabilities").header("Authorization", "Bearer synthetic-device-secret").build()).execute().use {
                        assertEquals(200, it.code)
                        assertEquals(okhttp3.Protocol.HTTP_2, it.protocol)
                        assertEquals(payload, it.body!!.string())
                    }
                    val received = host.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals(logical, received.getHeader("Host") ?: received.getHeader(":authority"))
                    assertEquals("Bearer synthetic-device-secret", received.getHeader("Authorization"))
                    assertNull(received.getHeader("Proxy-Authorization"))
                    assertTrue(bridge.headers.isNotEmpty())
                    assertEquals("Bearer $bootstrapToken", bridge.headers.first().getFieldValue("Authorization"))
                    assertFalse(bridge.headers.first().iterateHttpFields().asSequence().any { bridge.headers.first().getFieldValue(it).contains("synthetic-device-secret") })
                } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }
    @Test fun `large pinned HTTP2 response survives a paused body consumer without replay`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        withBridge(certificate) { host, bridge ->
            val endpoint = endpoint(certificate, bridge)
            RelayLoopbackProxy(endpoint).use { proxy ->
                val client = SecureTransport.client(endpoint, proxy)
                val consumer = java.util.concurrent.Executors.newSingleThreadExecutor()
                val headersRead = java.util.concurrent.CountDownLatch(1)
                val resume = java.util.concurrent.CountDownLatch(1)
                try {
                    val payload = "slow-synthetic:" + "z".repeat(2 * 1024 * 1024 - 32)
                    host.enqueue(MockResponse().setBody(payload))
                    val complete = consumer.submit<String> {
                        client.newCall(Request.Builder().url(endpoint.baseUrl + "/v1/snapshot").build()).execute().use {
                            assertEquals(200, it.code)
                            assertEquals(okhttp3.Protocol.HTTP_2, it.protocol)
                            headersRead.countDown()
                            assertTrue(resume.await(5, TimeUnit.SECONDS))
                            it.body!!.string()
                        }
                    }
                    assertTrue(headersRead.await(3, TimeUnit.SECONDS))
                    // Keep the app paused until a matching pong is queued behind the burst response.
                    assertTrue(bridge.pongSent.await(3, TimeUnit.SECONDS))
                    resume.countDown()
                    assertEquals(payload, complete.get(8, TimeUnit.SECONDS))
                    assertEquals("The response is not rescued by an HTTP retry", 1, host.requestCount)
                    assertEquals("Only one relay stream", 1, bridge.headers.size)
                } finally {
                    resume.countDown(); consumer.shutdownNow()
                    client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                }
            }
        }
    }
    @Test fun `relay cannot replace pinned host or bypass hostname PKIX or expiry before credentials`() {
        val ca = HeldCertificate.Builder().certificateAuthority(1).build()
        val valid = HeldCertificate.Builder().addSubjectAlternativeName(logical).signedBy(ca).build()
        val other = HeldCertificate.Builder().addSubjectAlternativeName(logical).signedBy(ca).build()
        val wrongName = HeldCertificate.Builder().addSubjectAlternativeName("other.invalid").signedBy(ca).build()
        val untrusted = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder().addSubjectAlternativeName(logical).signedBy(ca).validityInterval(now - 20_000, now - 10_000).build()
        for (leaf in listOf(other, wrongName, untrusted, expired)) {
            withBridge(leaf, if (leaf == untrusted) null else ca) { host, bridge ->
                val endpoint = endpoint(valid, bridge).copy(certificatePem = ca.certificatePem(), pinSha256 = CertificatePinner.pin(if (leaf == wrongName || leaf == expired) leaf.certificate else valid.certificate))
                RelayLoopbackProxy(endpoint).use { proxy ->
                    val client = SecureTransport.client(endpoint, proxy)
                    try {
                        assertThrows(java.io.IOException::class.java) {
                            client.newCall(Request.Builder().url(endpoint.baseUrl + "/v1/test").header("Authorization", "Bearer synthetic-never-send").build()).execute().close()
                        }
                        assertEquals(0, host.requestCount)
                    } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
            }
        }
    }
    @Test fun `pairing replaces bootstrap grant atomically and cold restore reuses only device grant`() = kotlinx.coroutines.runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        val deviceId = "f172cf3d-40e0-43e6-93c4-b27bdb3b8877"
        val deviceToken = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
        withBridge(certificate) { host, bridge ->
            val endpoint = endpoint(certificate, bridge)
            host.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/v1/pairings" -> json("""{"deviceId":"synthetic-device","deviceToken":"synthetic-device-bearer","hostName":"Synthetic","protocolVersion":1,"relayAccess":{"accessId":"$deviceId","accessToken":"$deviceToken","expiresAt":${System.currentTimeMillis() + 365L * 24 * 60 * 60_000}}}""", 201)
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":false,"textPrompt":false,"cancel":false,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}""")
                    else -> json("""{"items":[]}""")
                }
            }
            val store = object : SecureStateStore {
                var value = StoredState()
                override suspend fun read() = value
                override suspend fun write(value: StoredState) { this.value = value }
                override suspend fun clear() { value = StoredState() }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            val invitation = kotlinx.serialization.json.buildJsonObject {
                put("version", kotlinx.serialization.json.JsonPrimitive(2)); put("baseUrl", kotlinx.serialization.json.JsonPrimitive(endpoint.baseUrl))
                put("pinSha256", kotlinx.serialization.json.JsonPrimitive(endpoint.pinSha256)); put("certificatePem", kotlinx.serialization.json.JsonPrimitive(endpoint.certificatePem))
                put("pairingToken", kotlinx.serialization.json.JsonPrimitive("synthetic-pairing-token")); put("expiresAt", kotlinx.serialization.json.JsonPrimitive(endpoint.relay!!.expiresAt))
                put("relay", kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive(endpoint.relay.url)); put("routeId", kotlinx.serialization.json.JsonPrimitive(route))
                    put("accessId", kotlinx.serialization.json.JsonPrimitive(bootstrapId)); put("accessToken", kotlinx.serialization.json.JsonPrimitive(bootstrapToken)) })
            }.toString()
            val repo = NetworkMobileRepository(store, scope, true)
            try {
                kotlinx.coroutines.runBlocking { repo.setForeground(true); repo.pair(invitation, "Synthetic phone") }
                assertEquals(dev.dshmobile.app.model.ConnectionState.ONLINE, repo.state.value.connection)
                assertEquals(deviceToken, store.value.host!!.endpoint.relay!!.accessToken)
                assertEquals("synthetic-device-bearer", store.value.host!!.deviceToken)
                assertEquals("Bearer $bootstrapToken", bridge.headers.first().getFieldValue("Authorization"))
                assertTrue(bridge.headers.drop(1).all { it.getFieldValue("Authorization") == "Bearer $deviceToken" })
                kotlinx.coroutines.runBlocking { repo.setForeground(false) }
                repo.close()
                val count = bridge.headers.size
                val restored = NetworkMobileRepository(store, scope, true)
                try {
                    kotlinx.coroutines.runBlocking { restored.restore() }
                    assertEquals(count, bridge.headers.size)
                    assertTrue(restored.state.value.remoteMode)
                    assertEquals("127.0.0.1:${bridge.port}", restored.state.value.relayHost)
                    kotlinx.coroutines.runBlocking { restored.setForeground(true) }
                    assertEquals(dev.dshmobile.app.model.ConnectionState.ONLINE, restored.state.value.connection)
                    assertTrue(bridge.headers.drop(count).all { it.getFieldValue("Authorization") == "Bearer $deviceToken" })
                    kotlinx.coroutines.runBlocking { restored.forget() }
                    assertNull(store.value.host)
                } finally { restored.close() }
            } finally { repo.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }
    private fun json(value: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(value)

    @Test fun `outer WSS uses independent CA hostname and validity checks before sending relay grants`() {
        val inner = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        val outerCa = HeldCertificate.Builder().certificateAuthority(1).build()
        val valid = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(outerCa).build()
        val wrongName = HeldCertificate.Builder().addSubjectAlternativeName("wrong.invalid").signedBy(outerCa).build()
        val untrusted = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(outerCa).validityInterval(now - 20_000, now - 10_000).build()
        val previous = javax.net.ssl.SSLContext.getDefault()
        val ordinaryTrust = HandshakeCertificates.Builder().addTrustedCertificate(outerCa.certificate).build()
        val testDefault = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(ordinaryTrust.trustManager), null) }
        try {
            javax.net.ssl.SSLContext.setDefault(testDefault)
            for (outer in listOf(valid, wrongName, untrusted, expired)) {
                withBridge(inner, outerCertificate = outer, outerCa = if (outer == untrusted) null else outerCa) { host, bridge ->
                    val endpoint = endpoint(inner, bridge).let { it.copy(relay = it.relay!!.copy(url = "wss://localhost:${bridge.port}")) }
                    RelayLoopbackProxy(endpoint).use { proxy ->
                        val client = SecureTransport.client(endpoint, proxy)
                        try {
                            if (outer == valid) {
                                host.enqueue(MockResponse().setBody("outer and inner independently authenticated"))
                                client.newCall(Request.Builder().url(endpoint.baseUrl).build()).execute().use {
                                    assertEquals("outer and inner independently authenticated", it.body!!.string())
                                }
                                assertEquals(1, bridge.headers.size)
                            } else {
                                assertThrows(java.io.IOException::class.java) { client.newCall(Request.Builder().url(endpoint.baseUrl).build()).execute().close() }
                                assertTrue("No WS upgrade, therefore no grant header, before outer TLS auth", bridge.headers.isEmpty())
                                assertEquals(0, host.requestCount)
                            }
                        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                    }
                }
            }
        } finally { javax.net.ssl.SSLContext.setDefault(previous) }
    }
    @Test fun `unrelated pongs cannot hide the oldest unconfirmed upload deadline`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        withBridge(certificate) { _, bridge ->
            bridge.wrongPongs = true
            val endpoint = endpoint(certificate, bridge)
            RelayLoopbackProxy(endpoint).use { proxy ->
                val target = proxy.proxy.address() as InetSocketAddress
                val synthetic = Request.Builder().url(endpoint.baseUrl).method("CONNECT", null).build()
                val challenge = okhttp3.Response.Builder().request(synthetic).protocol(okhttp3.Protocol.HTTP_1_1).code(407).message("Auth")
                    .header("Proxy-Authenticate", "OkHttp-Preemptive").build()
                val routeValue = okhttp3.Route(okhttp3.Address(logical, 443, okhttp3.Dns.SYSTEM, javax.net.SocketFactory.getDefault(),
                    javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory, javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier(),
                    CertificatePinner.DEFAULT, okhttp3.Authenticator.NONE, proxy.proxy, listOf(okhttp3.Protocol.HTTP_1_1), listOf(okhttp3.ConnectionSpec.MODERN_TLS),
                    java.net.ProxySelector.getDefault()), proxy.proxy, target)
                val auth = proxy.authenticator.authenticate(routeValue, challenge)!!.header("Proxy-Authorization")!!
                Socket(target.address, target.port).use { socket ->
                    socket.soTimeout = 12_000
                    socket.getOutputStream().write("CONNECT $logical:443 HTTP/1.1\r\nHost: $logical:443\r\nProxy-Authorization: $auth\r\n\r\n".toByteArray())
                    val input = socket.getInputStream()
                    var tail = 0
                    while (tail != 0x0d0a0d0a) { val next = input.read(); assertTrue(next >= 0); tail = (tail shl 8) or next }
                    val started = System.nanoTime()
                    socket.getOutputStream().write(byteArrayOf(1, 2, 3)); socket.getOutputStream().flush()
                    assertEquals(-1, input.read())
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    assertTrue("Oldest data expires independently of arbitrary pongs", elapsed in 9_000..11_500)
                }
            }
        }
    }
    @Test fun `rapid valid uploads coalesce ping barriers rather than tripping relay control rate`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        withBridge(certificate) { host, bridge ->
            val endpoint = endpoint(certificate, bridge)
            RelayLoopbackProxy(endpoint).use { proxy ->
                val client = SecureTransport.client(endpoint, proxy)
                try {
                    repeat(30) { index ->
                        host.enqueue(MockResponse().setBody("ok-$index"))
                        client.newCall(Request.Builder().url(endpoint.baseUrl + "/v1/test/$index").build()).execute().use {
                            assertEquals("ok-$index", it.body!!.string())
                        }
                    }
                    assertEquals(30, host.requestCount)
                    assertTrue("Barriers are at most one per second", bridge.maxPingsInWindow <= 1)
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }
    @Test fun `unauthenticated and arbitrary loopback CONNECT never open a relay stream`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(logical).build()
        withBridge(certificate) { _, bridge ->
            RelayLoopbackProxy(endpoint(certificate, bridge)).use { proxy ->
                val address = proxy.proxy.address() as InetSocketAddress
                for (authority in listOf("arbitrary.invalid:443", "$logical:443")) {
                    Socket(address.address, address.port).use { socket ->
                        socket.soTimeout = 2000
                        socket.getOutputStream().write("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n".toByteArray())
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                assertTrue(bridge.headers.isEmpty())
            }
        }
    }
    private fun endpoint(certificate: HeldCertificate, bridge: Bridge) = HostEndpoint("https://$logical", CertificatePinner.pin(certificate.certificate), certificate.certificatePem(),
        RelaySettings("ws://127.0.0.1:${bridge.port}", route, bootstrapId, bootstrapToken, System.currentTimeMillis() + 600_000))

    private fun withBridge(certificate: HeldCertificate, ca: HeldCertificate? = null, outerCertificate: HeldCertificate? = null, outerCa: HeldCertificate? = null,
        block: (MockWebServer, Bridge) -> Unit) {
        val tls = HandshakeCertificates.Builder().apply { if (ca == null) heldCertificate(certificate) else heldCertificate(certificate, ca.certificate) }.build()
        MockWebServer().use { host ->
            host.useHttps(tls.sslSocketFactory(), false)
            host.start()
            val bridge = Bridge(host.port)
            if (outerCertificate != null) {
                val outerTls = HandshakeCertificates.Builder().apply {
                    if (outerCa == null) heldCertificate(outerCertificate) else heldCertificate(outerCertificate, outerCa.certificate)
                }.build()
                val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(arrayOf(outerTls.keyManager), null, null) }
                bridge.setWebSocketFactory(org.java_websocket.server.DefaultSSLWebSocketServerFactory(ssl))
            }
            try { bridge.start(); assertTrue(bridge.started.await(5, TimeUnit.SECONDS)); block(host, bridge) }
            finally { bridge.connections.toList().forEach { it.closeConnection(1000, "fixture done") }; bridge.stop(1000) }
        }
    }
    private class Bridge(private val target: Int) : WebSocketServer(InetSocketAddress("127.0.0.1", 0), 1, listOf(RelayWebSocketDraft())) {
        val started = java.util.concurrent.CountDownLatch(1)
        val headers = CopyOnWriteArrayList<ClientHandshake>()
        val pongSent = java.util.concurrent.CountDownLatch(1)
        private val pipes = ConcurrentHashMap<WebSocket, Socket>()
        private val pingTimes = CopyOnWriteArrayList<Long>()
        @Volatile var maxPingsInWindow = 0
        @Volatile var wrongPongs = false
        override fun onWebsocketPing(connection: WebSocket, frame: org.java_websocket.framing.Framedata) {
            val now = System.nanoTime()
            pingTimes += now
            maxPingsInWindow = maxOf(maxPingsInWindow, pingTimes.count { now - it < TimeUnit.SECONDS.toNanos(1) })
            if (wrongPongs) {
                connection.sendFrame(org.java_websocket.framing.PongFrame().apply { setPayload(ByteBuffer.wrap(byteArrayOf(9))) })
            } else {
                super.onWebsocketPing(connection, frame)
                pongSent.countDown()
            }
        }
        override fun onStart() { started.countDown() }
        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            headers += handshake
            if (wrongPongs) { connection.send("{\"type\":\"ready\",\"version\":1}".toByteArray()); return }
            val socket = Socket("127.0.0.1", target)
            pipes[connection] = socket
            connection.send("{\"type\":\"ready\",\"version\":1}".toByteArray())
            Thread {
                try {
                    val bytes = ByteArray(16 * 1024)
                    while (true) {
                        val count = socket.getInputStream().read(bytes)
                        if (count < 0) break
                        while (connection.hasBufferedData() && connection.isOpen) Thread.sleep(1)
                        if (!connection.isOpen) break
                        connection.send(bytes.copyOf(count))
                    }
                } catch (_: Exception) { }
                finally { connection.closeConnection(1000, "fixture EOF"); socket.close() }
            }.apply { isDaemon = true; start() }
        }
        override fun onMessage(connection: WebSocket, message: String) { connection.closeConnection(1002, "binary only") }
        override fun onMessage(connection: WebSocket, bytes: ByteBuffer) {
            pipes[connection]?.getOutputStream()?.apply { write(ByteArray(bytes.remaining()).also { bytes.get(it) }); flush() }
        }
        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) { pipes.remove(connection)?.close() }
        override fun onError(connection: WebSocket?, exception: Exception) { connection?.closeConnection(1006, "fixture error") }
    }
}
