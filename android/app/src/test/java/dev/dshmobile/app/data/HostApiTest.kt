package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HostApiTest {
    @Test fun `transparent gzip decodes bounded JSON and sequential reads reuse the connection`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = java.io.ByteArrayOutputStream().also { output ->
                java.util.zip.GZIPOutputStream(output).use { it.write("{\"items\":[]}".toByteArray()) }
            }.toByteArray()
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip").setBody(okio.Buffer().write(bytes))) }
            val api = api(server)
            try {
                assertTrue(api.workspaces().isEmpty()); assertTrue(api.workspaces().isEmpty())
                val first = server.takeRequest(); val second = server.takeRequest()
                assertEquals("gzip", first.getHeader("Accept-Encoding"))
                assertEquals(0, first.sequenceNumber); assertEquals(1, second.sequenceNumber)
            } finally { api.close() }
        }
    }

    @Test fun `notification SSE authenticates explicit cursor and preserves bounded pages`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: notification-page\ndata: {\"version\":1,\"epoch\":\"epoch\",\"items\":[],\"pending\":[],\"nextCursor\":\"head\",\"hasMore\":false,\"resetRequired\":true,\"coverage\":\"ready\"}\n\n"))
            val api = api(server); val result = CompletableDeferred<NotificationPage>()
            val source = api.observeNotifications("cursor", { result.complete(it) }, { _, _ -> })
            try {
                assertTrue(withTimeout(5000) { result.await() }.resetRequired)
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/v1/notification-events/stream?after=cursor", request.path)
                assertEquals("Bearer synthetic-device-token-for-tests", request.getHeader("Authorization")); assertNull(request.getHeader("Last-Event-ID"))
            } finally { source.cancel(); api.close() }
        }
    }
    @Test fun `notification retry retains 429 delay and oversize unknown frame fails before parsing`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val api = api(server); val failure = CompletableDeferred<Pair<String, Long>>()
            val source = api.observeNotifications(null, {}, { key, delay -> failure.complete(key to delay) })
            try { assertEquals("rate_limited" to 120000L, withTimeout(5000) { failure.await() }) } finally { source.cancel(); api.close() }
        }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: future\ndata: " + "a".repeat(128 * 1024 + 1)))
            val api = api(server); val failure = CompletableDeferred<String>()
            val source = api.observeNotifications(null, {}, { key, _ -> failure.complete(key) })
            try { assertEquals("invalid_response", withTimeout(5000) { failure.await() }) } finally { source.cancel(); api.close() }
        }
    }
    @Test fun `oversized unterminated or unknown SSE event fails before parser accumulates it`() = runBlocking {
        for (prefix in listOf("event: snapshot\ndata: ", "event: unknown\ndata: ")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(prefix + "a".repeat(2 * 1024 * 1024 + 1)))
                val api = api(server)
                val failure = CompletableDeferred<String>()
                val source = api.observe("chat", { fail("Oversized event must not publish a snapshot") }, { failure.complete(it.key) })
                try { assertEquals("chat_unavailable", withTimeout(5_000) { failure.await() }) }
                finally { source.cancel(); api.close() }
            }
        }
    }
    @Test fun `SSE connection outlives mutation timeout with heartbeat and second snapshot`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val first = snapshot("first", "Before wait", false, 1)
            val second = snapshot("second", "After wait", false, 2)
            val initial = "event: snapshot\ndata: $first\n\n: heartbeat\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(initial + "event: snapshot\ndata: $second\n\n").throttleBody(initial.toByteArray().size.toLong(), 16, TimeUnit.SECONDS))
            val snapshots = LinkedBlockingQueue<SessionSnapshot>()
            val errors = LinkedBlockingQueue<String>()
            val api = api(server)
            val source = api.observe("chat", { snapshots.offer(it) }, { errors.offer(it.key) })
            try {
                assertEquals("first", snapshots.poll(5, TimeUnit.SECONDS)!!.messages.single().id)
                assertEquals("second", snapshots.poll(20, TimeUnit.SECONDS)!!.messages.single().id)
            } finally { source.cancel(); api.close() }
        }
    }
    @Test fun `authenticated SSE preserves complete replacement snapshots and no bearer URL`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val first = snapshot("old", "Old provisional", true, 8)
            val second = snapshot("canonical", "New canonical", false, 2)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: snapshot\ndata: $first\n\n: heartbeat\n\nevent: snapshot\ndata: $second\n\n"))
            val queue = LinkedBlockingQueue<SessionSnapshot>()
            val api = api(server)
            val source = api.observe("chat", { queue.offer(it) }, {})
            try {
                assertEquals("old", queue.poll(5, TimeUnit.SECONDS)!!.messages.single().id)
                assertEquals(listOf("canonical"), queue.poll(5, TimeUnit.SECONDS)!!.messages.map { it.id })
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("Bearer synthetic-device-token-for-tests", request.getHeader("Authorization"))
                assertEquals("/v1/sessions/chat/events", request.path)
                assertNull(request.getHeader("Last-Event-ID"))
            } finally { source.cancel(); api.close() }
        }
    }
    @Test fun `session index drains opaque cursors and rejects repeated cursors or duplicate IDs`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                server.enqueue(json("""{"items":[${session("chat")}],"nextCursor":"page2+/="}"""))
                server.enqueue(json("""{"items":[${session("other")}],"nextCursor":null}"""))
                assertEquals(listOf("chat", "other"), api.sessions().items.map { it.id })
                server.takeRequest()
                assertEquals("page2+/=", server.takeRequest().requestUrl!!.queryParameter("cursor"))
                server.enqueue(json("""{"items":[${session("chat")}],"nextCursor":"page2"}"""))
                server.enqueue(json("""{"items":[${session("other")}],"nextCursor":"page2"}"""))
                try { api.sessions(); fail("Repeated cursor") } catch (failure: MobileFailure) { assertEquals("invalid_response", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `first page continuation preserves duplicate and cursor validation at chunk boundary`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                for (next in listOf("page2+/=", "страница🌍")) {
                    server.enqueue(json("""{"items":[${session("chat")}],"nextCursor":"$next"}"""))
                    val first = api.sessions(firstPageOnly = true)
                    assertTrue(first.truncated); assertEquals(1, first.pages)
                    server.enqueue(json("""{"items":[${session("chat")}],"nextCursor":null}"""))
                    try { api.sessions(after = first); fail("Duplicate across first/background page") }
                    catch (failure: MobileFailure) { assertEquals("invalid_response", failure.key) }
                }
                server.enqueue(json("""{"items":[],"nextCursor":null}"""))
                assertFalse(api.sessions(firstPageOnly = true).truncated)
                server.enqueue(json("""{"items":[],"nextCursor":"nonempty"}"""))
                try { api.sessions(firstPageOnly = true); fail("Empty page cannot continue") }
                catch (failure: MobileFailure) { assertEquals("invalid_response", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `unknown receipt status is uncertain and malformed required fields are not defaulted`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                val id = "a172cf3d-40e0-43e6-93c4-b27bdb3b8877"
                server.enqueue(json("""{"requestId":"$id","status":"future-status","updatedAt":10}"""))
                assertEquals("uncertain", api.receipt(id)!!.status)
                server.enqueue(json("""{"session":${session("chat")},"cursor":1,"hasMore":false,"activity":"idle"}"""))
                try { api.snapshot("chat"); fail("Missing messages") } catch (failure: MobileFailure) { assertEquals("chat_unavailable", failure.key) }
                server.enqueue(json("""{"error":{"code":"unauthorized","message":"SYNTHETIC SECRET MUST NOT SURFACE","retryable":false}}""", 401))
                try { api.capabilities(); fail("Unauthorized") } catch (failure: MobileFailure) { assertEquals("unauthorized", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `malformed SSE history fails once and suppresses later snapshots and transport close`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val valid = snapshot("late", "Synthetic late snapshot", false, 1)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: snapshot\ndata: {}\n\nevent: snapshot\ndata: $valid\n\n"))
            val api = api(server)
            val errors = LinkedBlockingQueue<String>()
            val snapshots = LinkedBlockingQueue<SessionSnapshot>()
            val source = api.observe("chat", { snapshots.offer(it) }, { errors.offer(it.key) })
            try {
                assertEquals("chat_unavailable", errors.poll(5, TimeUnit.SECONDS))
                assertNull("Cancellation or EOF must not replace the scoped failure", errors.poll(1, TimeUnit.SECONDS))
                assertTrue("No later frame after terminal decode failure", snapshots.isEmpty())
            } finally { source.cancel(); api.close() }
        }
    }
    @Test fun `ledger recovery hold is a distinct host decision, not a transport outage`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"error":{"code":"recovery_hold","message":"Synthetic","retryable":false}}""", 503))
            val api = api(server)
            try {
                try {
                    api.command(StoredCommand("00000000-0000-4000-8000-000000000001", "send", "chat", "Synthetic"))
                    fail("A held command must not produce a receipt")
                } catch (failure: MobileFailure) { assertEquals("recovery_hold", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `snapshot HTTP service failure is chat scoped and not a transport outage`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                server.enqueue(json("""{"error":{"code":"unavailable","message":"Synthetic","retryable":true}}""", 503))
                try { api.snapshot("chat"); fail("Unavailable history must fail") }
                catch (failure: MobileFailure) { assertEquals("chat_unavailable", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `oversize snapshot HTTP response is chat scoped without weakening byte limit`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                server.enqueue(json("""{"error":{"code":"payload_too_large","message":"Synthetic","retryable":false}}""", 413))
                try { api.snapshot("chat"); fail("Oversize host error must fail") }
                catch (failure: MobileFailure) { assertEquals("chat_unavailable", failure.key) }
                server.enqueue(json("x".repeat(2 * 1024 * 1024 + 1)))
                try { api.snapshot("chat"); fail("Oversize body must not decode") }
                catch (failure: MobileFailure) { assertEquals("chat_unavailable", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `SSE HTTP service failures remain chat scoped but revoked access is terminal`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                for (status in listOf(413, 503, 500)) {
                    server.enqueue(json("""{"error":{"code":"unavailable","message":"Synthetic","retryable":true}}""", status))
                    val result = CompletableDeferred<String>()
                    val source = api.observe("chat", {}, { result.complete(it.key) })
                    try { assertEquals("chat_unavailable", withTimeout(5_000) { result.await() }) }
                    finally { source.cancel() }
                }
                server.enqueue(json("""{"error":{"code":"revoked","message":"Synthetic","retryable":false}}""", 401))
                val result = CompletableDeferred<String>()
                val source = api.observe("chat", {}, { result.complete(it.key) })
                try { assertEquals("unauthorized", withTimeout(5_000) { result.await() }) }
                finally { source.cancel() }
            } finally { api.close() }
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `silent notification socket times out and reconnects from durable cursor`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(": heartbeat\n\n").setHeader("Content-Length", "100000")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN))
            // Keep the first body incomplete without EOF, so this tests socket read timeout, not close.
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: notification-page\ndata: {\"version\":1,\"epoch\":\"epoch\",\"items\":[],\"nextCursor\":\"head\",\"hasMore\":false,\"resetRequired\":false,\"coverage\":\"ready\"}\n\n"))
            val api = api(server)
            val first = CompletableDeferred<String>()
            val start = System.nanoTime()
            val source = api.observeNotifications("durable", {}, { key, _ -> first.complete(key) })
            try {
                assertEquals("network_unavailable", withTimeout(30_000) { first.await() })
                val elapsed = (System.nanoTime() - start) / 1_000_000
                assertTrue("elapsed=$elapsed", elapsed in 24_000..29_000)
                source.cancel(); api.closeAndAwait()
                val fresh = api(server)
                val page = CompletableDeferred<NotificationPage>()
                // A finite fixture body emits onClosed after onPage. Await the page, not a race
                // between the independently completed page and EOF-failure deferreds.
                val next = fresh.observeNotifications("durable", { page.complete(it) }, { _, _ -> })
                try { assertEquals("head", withTimeout(5000) { page.await() }.nextCursor) }
                finally { next.cancel(); fresh.closeAndAwait() }
                assertEquals("/v1/notification-events/stream?after=durable", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertEquals("/v1/notification-events/stream?after=durable", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            } finally { source.cancel(); api.close() }
        }
    }

    private fun api(server: MockWebServer) = HostApi(HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/")), "synthetic-device-token-for-tests")
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun session(id: String) = """{"id":"$id","title":"Synthetic","workspaceId":"workspace","updatedAt":10,"running":false,"canExecute":true}"""
    private fun snapshot(id: String, text: String, provisional: Boolean, cursor: Long) = """{"session":${session("chat")},"messages":[{"id":"$id","role":"assistant","text":"$text","createdAt":10,"provisional":$provisional}],"cursor":$cursor,"hasMore":false,"activity":"idle"}"""
}
