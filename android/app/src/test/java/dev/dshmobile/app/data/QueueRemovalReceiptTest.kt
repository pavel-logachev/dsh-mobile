package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QueueRemovalReceiptTest {
    private class Store : SecureStateStore {
        var value = StoredState()
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    private class Fixture : Dispatcher() {
        val posts = CopyOnWriteArrayList<RecordedRequest>()
        val polls = AtomicInteger()
        @Volatile var result = "accepted"
        @Volatile var removed = false
        var loseResponse = false
        val session = """{"id":"chat","title":"Synthetic queue","workspaceId":"workspace","updatedAt":10,"running":true,"canExecute":true}"""
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.requestUrl!!.encodedPath) {
                "/v1/pairings" -> json("""{"deviceId":"synthetic-device","deviceToken":"synthetic-device-token","hostName":"Synthetic","protocolVersion":1}""", 201)
                "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false,"queue":true}}""")
                "/v1/workspaces" -> json("""{"items":[{"id":"workspace","name":"Synthetic","canExecute":true}]}""")
                "/v1/presets" -> json("""{"items":[]}""")
                "/v1/sessions" -> json("""{"items":[$session],"nextCursor":null}""")
                "/v1/sessions/chat" -> json("""{"session":$session,"messages":[],"cursor":${if (removed) 2 else 1},"hasMore":false,"activity":"running","pendingQueue":${if (removed) "[]" else """[{"id":"item","text":"Synthetic queued message","delivery":"queue"}]"""}}""")
                "/v1/sessions/chat/queue" -> {
                    posts += request
                    if (loseResponse) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else receipt(requestId(request), "pending").setResponseCode(202)
                }
                else -> if (request.requestUrl!!.encodedPath.startsWith("/v1/commands/")) {
                    val status = if (polls.incrementAndGet() == 1) "pending" else result
                    if (status == "accepted") removed = true
                    receipt(request.requestUrl!!.pathSegments.last(), status)
                } else json("{}", 404)
            }
        }
    }
    @Test fun `pending removal polls to acceptance and authoritative disappearance without another POST`() = runBlocking {
        withRepository { repo, store, fixture ->
            repo.queueAction("item", "remove")
            assertEquals("pending", repo.state.value.pending?.status)
            assertNull(repo.state.value.error); assertEquals(1, repo.state.value.snapshot!!.pendingQueue.size)
            withTimeout(3000) { repo.state.first { it.pending == null && it.snapshot!!.pendingQueue.isEmpty() } }
            assertNull(store.value.pending); assertNull(repo.state.value.error)
            assertEquals(2, fixture.polls.get()); assertSingleRemoval(fixture)
        }
    }
    @Test fun `definite polled rejection retains queue and shows error without another POST`() = runBlocking {
        withRepository { repo, store, fixture ->
            fixture.result = "rejected"
            repo.queueAction("item", "remove")
            withTimeout(3000) { repo.state.first { it.pending == null && it.error == "command_rejected" } }
            assertNull(store.value.pending); assertEquals(1, repo.state.value.snapshot!!.pendingQueue.size)
            assertEquals(2, fixture.polls.get()); assertSingleRemoval(fixture)
        }
    }
    @Test fun `poll budget exhaustion stays uncertain and manual rejection never resends`() = runBlocking {
        withRepository { repo, store, fixture ->
            fixture.result = "pending"
            repo.queueAction("item", "remove")
            withTimeout(15000) { repo.state.first { it.pending?.status == "uncertain" } }
            assertEquals("uncertain", store.value.pending?.status)
            assertEquals("command_uncertain", repo.state.value.error); assertEquals(6, fixture.polls.get())
            assertEquals(1, repo.state.value.snapshot!!.pendingQueue.size); assertSingleRemoval(fixture)
            fixture.result = "rejected"; repo.resolvePending()
            assertNull(store.value.pending); assertEquals("command_rejected", repo.state.value.error)
            assertSingleRemoval(fixture)
        }
    }
    @Test fun `lost removal response remains uncertain until explicit receipt check and never resends`() = runBlocking {
        withRepository { repo, store, fixture ->
            fixture.loseResponse = true
            repo.queueAction("item", "remove")
            assertEquals("uncertain", repo.state.value.pending?.status)
            // The transport diagnostic remains network_unavailable; delivery itself is uncertain.
            assertEquals("network_unavailable", repo.state.value.error)
            assertEquals(1, fixture.polls.get()); assertSingleRemoval(fixture)
            repo.resolvePending()
            assertNull(store.value.pending); assertNull(repo.state.value.error)
            assertTrue(repo.state.value.snapshot!!.pendingQueue.isEmpty()); assertSingleRemoval(fixture)
        }
    }
    @Test fun `background pauses removal polling and foreground reconciles same command without resend`() = runBlocking {
        withRepository { repo, store, fixture ->
            repo.queueAction("item", "remove")
            val id = store.value.pending!!.requestId
            repo.setForeground(false)
            assertEquals(id, store.value.pending?.requestId); assertEquals("pending", store.value.pending?.status)
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            repo.setForeground(true)
            withTimeout(3000) { repo.state.first { it.pending == null && it.snapshot!!.pendingQueue.isEmpty() } }
            assertNull(store.value.pending); assertSingleRemoval(fixture)
            assertEquals(id, requestId(fixture.posts.single()))
        }
    }
    @Test fun `abandoning removal cannot let old receipt clear a new user command`() = runBlocking {
        withRepository { repo, store, fixture ->
            repo.queueAction("item", "remove")
            val old = store.value.pending!!.requestId
            repo.abandonPending(); repo.resolvePending()
            assertNull(store.value.pending); assertEquals(1, repo.state.value.snapshot!!.pendingQueue.size)
            // A second deliberate user action has a fresh identity, never an automatic resend.
            repo.queueAction("item", "remove")
            val current = store.value.pending!!.requestId
            assertNotEquals(old, current)
            withTimeout(3000) { repo.state.first { it.pending == null && it.snapshot!!.pendingQueue.isEmpty() } }
            assertEquals(listOf(old, current), fixture.posts.map(::requestId))
            assertEquals(2, fixture.polls.get())
        }
    }
    private suspend fun withRepository(block: suspend (NetworkMobileRepository, Store, Fixture) -> Unit) {
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val store = Store(); val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start()
            val repo = NetworkMobileRepository(store, scope, debug = true,
                preHeaderRetryDelay = { error("Queue removal must never automatically resend") })
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic")
                repo.selectSession("chat")
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                block(repo, store, fixture)
            } finally { repo.close(); scope.cancel() }
        }
    }
    private fun assertSingleRemoval(fixture: Fixture) {
        assertEquals("Only the user-admitted POST may exist", 1, fixture.posts.size)
        val body = fixture.posts.single().body.clone().readUtf8()
        assertTrue(body.contains("\"itemId\":\"item\"")); assertTrue(body.contains("\"action\":\"remove\""))
    }
    companion object {
        private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        private fun receipt(id: String, status: String) = json("""{"requestId":"$id","status":"$status","updatedAt":20}""")
        private fun requestId(request: RecordedRequest): String = (mobileJson.parseToJsonElement(request.body.clone().readUtf8()) as kotlinx.serialization.json.JsonObject)["requestId"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }
}
