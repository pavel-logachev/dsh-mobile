package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhoneTransportTest {
    private fun api(server: MockWebServer) = HostApi(HostEndpoint(server.url("/").toString(), null), "synthetic-device-token")
    @Test fun `upload streams exact length unicode filename and bearer then image GET is bounded and authorized`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setResponseCode(201).setBody("""{"uploadId":"upload","expiresAt":1000,"attachment":{"id":"image","name":"фото.png","size":3,"mime":"image/png","kind":"image"}}"""))
                val receipt = api.upload("chat", "фото.png", "image/png", 3, { byteArrayOf(1,2,3).inputStream() }, {})
                assertEquals("upload", receipt.uploadId)
                val request = server.takeRequest(); assertEquals("/v1/sessions/chat/uploads", request.path)
                assertEquals("3", request.getHeader("Content-Length")); assertNull(request.getHeader("Transfer-Encoding"))
                assertEquals("%D1%84%D0%BE%D1%82%D0%BE.png", request.getHeader("X-File-Name")); assertEquals("Bearer synthetic-device-token", request.getHeader("Authorization")); assertEquals(3L, request.bodySize)
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(byteArrayOf(1,2,3))))
                assertArrayEquals(byteArrayOf(1,2,3), api.image("chat", "image")); assertEquals("/v1/sessions/chat/attachments/image", server.takeRequest().path)
            } finally { api.close() }
        }
    }
    @Test fun `upload allows an active body beyond ordinary metadata deadline without replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setResponseCode(201).setBody("""{"uploadId":"slow-upload","expiresAt":1000,"attachment":{"id":"image","name":"slow.jpg","size":5242880,"mime":"image/jpeg","kind":"image"}}"""))
                var position = 0
                var nextChunkAt = System.nanoTime()
                val stream = object : java.io.InputStream() {
                    override fun read(): Int = if (position < 5242880) { position++; 73 } else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (position >= 5242880) return -1
                        // 160 * 110 ms = 17.6 s, with continuous forward progress.
                        nextChunkAt += 110_000_000L
                        val remaining = nextChunkAt - System.nanoTime()
                        if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining)
                        val count = minOf(length, 5242880 - position)
                        java.util.Arrays.fill(buffer, offset, offset + count, 73.toByte()); position += count
                        return count
                    }
                }
                val receipt = api.upload("chat", "slow.jpg", "image/jpeg", 5242880, { stream }, {})
                assertEquals("slow-upload", receipt.uploadId)
                assertEquals(5242880L, server.takeRequest().bodySize)
                assertEquals("Upload must not replay", 1, server.requestCount)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun `upload rejects invalid length without opening source or sending`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                for (size in listOf(0L, -1L, PhoneRules.FILE_LIMIT + 1, Long.MAX_VALUE)) {
                    try { api.upload("chat", "x.png", "image/png", size, { error("must not open") }, {}); fail("accepted $size") }
                    catch (failure: MobileFailure) { assertEquals("invalid_selection", failure.key) }
                }
                assertEquals(0, server.requestCount)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun `upload HTTP rejection and closed response are never replayed`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setResponseCode(415).setHeader("Content-Type", "application/json").setBody("""{"error":{"code":"unsupported_media_type","message":"Synthetic","retryable":false}}"""))
                try { api.upload("chat", "x.png", "image/png", 1, { byteArrayOf(1).inputStream() }, {}); fail("accepted rejection") }
                catch (failure: MobileFailure) { assertEquals("host_error", failure.key) }
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
                try { api.upload("chat", "x.png", "image/png", 1, { byteArrayOf(1).inputStream() }, {}); fail("accepted closed response") }
                catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
                assertEquals(2, server.requestCount)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun `cancelled upload closes source without replay and later upload still works`() = runBlocking {
        MockWebServer().use { server ->
            // Bind and connect to the same address. On dual-stack localhost, cancellation can
            // postpone the IPv4 route and select ::1, where this IPv4-only fixture isn't listening.
            val loopback = java.net.InetAddress.getByName("127.0.0.1")
            server.start(loopback, 0)
            val endpoint = server.url("/").newBuilder().host(loopback.hostAddress!!).build().toString()
            val api = HostApi(HostEndpoint(endpoint, null), "synthetic-device-token")
            val closed = java.util.concurrent.CountDownLatch(1)
            try {
                server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                val job = launch {
                    api.upload("chat", "x.png", "image/png", 1, { object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
                        override fun close() { super.close(); closed.countDown() }
                    } }, {})
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
                job.cancel(); job.join()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody("""{"uploadId":"next","expiresAt":1000,"attachment":{"id":"image","name":"x.png","size":1,"mime":"image/png","kind":"image"}}"""))
                assertEquals("next", api.upload("chat", "x.png", "image/png", 1, { byteArrayOf(1).inputStream() }, {}).uploadId)
                assertEquals(2, server.requestCount)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun `discard upload accepts empty 204 without JSON content type`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setResponseCode(204))
                api.discardUpload("chat", "upload")
                val request = server.takeRequest()
                assertEquals("DELETE", request.method)
                assertEquals("/v1/sessions/chat/uploads/upload", request.path)
                assertEquals(1, server.requestCount)
            } finally { api.close() }
        }
    }
    @Test fun `file download uses read bearer route bounded bytes and rejects non binary`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("Synthetic"))
                assertEquals("Synthetic", api.file("chat", "file").toString(Charsets.UTF_8))
                val request = server.takeRequest(); assertEquals("/v1/sessions/chat/files/file", request.path); assertEquals("Bearer synthetic-device-token", request.getHeader("Authorization"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("Synthetic"))
                try { api.file("chat", "file"); fail("HTML accepted") } catch (failure: MobileFailure) { assertEquals("chat_unavailable", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `new mutations use exact durable request id and route without replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                val commands = listOf(
                    StoredCommand(UUID.randomUUID().toString(), "queue", sessionId = "chat", itemId = "item", action = "remove") to "queue",
                    StoredCommand(UUID.randomUUID().toString(), "rename", sessionId = "chat", title = "Synthetic") to "title",
                    StoredCommand(UUID.randomUUID().toString(), "model", sessionId = "chat", selection = ModelSelection("fixture", "model")) to "model",
                    StoredCommand(UUID.randomUUID().toString(), "answer", sessionId = "chat", callId = "call", answer = QuestionAnswer(listOf(AnswerItem("q", listOf("Yes"))))) to "answers")
                commands.forEach { (command, route) ->
                    server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody("""{"requestId":"${command.requestId}","status":"accepted","updatedAt":1}"""))
                    assertEquals("accepted", api.command(command).status)
                    val request = server.takeRequest(); assertEquals("/v1/sessions/chat/$route", request.path); assertTrue(request.body.readUtf8().contains(command.requestId))
                }
                assertEquals(4, server.requestCount)
            } finally { api.close() }
        }
    }
}
