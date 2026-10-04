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
    @Test fun `oversized unterminated or unknown SSE event fails before parser accumulates it`() = runBlocking {
        for (prefix in listOf("event: snapshot\ndata: ", "event: unknown\ndata: ")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(prefix + "a".repeat(2 * 1024 * 1024 + 1)))
                val api = api(server)
                val failure = CompletableDeferred<String>()
                val source = api.observe("chat", { fail("Oversized event must not publish a snapshot") }, { failure.complete(it.key) })
                try { assertEquals("invalid_response", withTimeout(5_000) { failure.await() }) }
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
    @Test fun `unknown receipt status is uncertain and malformed required fields are not defaulted`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = api(server)
            try {
                val id = "a172cf3d-40e0-43e6-93c4-b27bdb3b8877"
                server.enqueue(json("""{"requestId":"$id","status":"future-status","updatedAt":10}"""))
                assertEquals("uncertain", api.receipt(id)!!.status)
                server.enqueue(json("""{"session":${session("chat")},"cursor":1,"hasMore":false,"activity":"idle"}"""))
                try { api.snapshot("chat"); fail("Missing messages") } catch (failure: MobileFailure) { assertEquals("invalid_response", failure.key) }
                server.enqueue(json("""{"error":{"code":"unauthorized","message":"SYNTHETIC SECRET MUST NOT SURFACE","retryable":false}}""", 401))
                try { api.capabilities(); fail("Unauthorized") } catch (failure: MobileFailure) { assertEquals("unauthorized", failure.key) }
            } finally { api.close() }
        }
    }
    private fun api(server: MockWebServer) = HostApi(HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/")), "synthetic-device-token-for-tests")
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun session(id: String) = """{"id":"$id","title":"Synthetic","workspaceId":"workspace","updatedAt":10,"running":false,"canExecute":true}"""
    private fun snapshot(id: String, text: String, provisional: Boolean, cursor: Long) = """{"session":${session("chat")},"messages":[{"id":"$id","role":"assistant","text":"$text","createdAt":10,"provisional":$provisional}],"cursor":$cursor,"hasMore":false,"activity":"idle"}"""
}
