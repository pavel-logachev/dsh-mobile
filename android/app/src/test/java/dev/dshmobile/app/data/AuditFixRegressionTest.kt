package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AuditFixRegressionTest {
    private class Store : SecureStateStore {
        var value = StoredState()
        var resets = 0
        var failReset = false
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { throw MobileFailure("storage_failed") }
        override suspend fun reset() { resets++; if (failReset) throw MobileFailure("storage_failed"); value = StoredState() }
    }
    private class Fixture : Dispatcher() {
        var failures = 0
        var permanent = false
        @Volatile var calls = 0
        var beforeMessage: (() -> Unit)? = null
        var outcomes = true
        fun snapshot() = """{"session":{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true},"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}"""
        fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
            "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false,"promptOutcomes":$outcomes}}""")
            "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
            "/v1/presets" -> json("""{"items":[]}""")
            "/v1/sessions" -> json("""{"items":[{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}],"nextCursor":null}""")
            "/v1/sessions/chat/messages" -> { beforeMessage?.invoke(); val requestId = mobileJson.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("requestId").jsonPrimitive.content; json("""{"requestId":"$requestId","status":"accepted","updatedAt":1}""") }
            "/v1/sessions/chat" -> { calls++; if (failures-- > 0) json("""{"error":{"code":"${if (permanent) "payload_too_large" else "unavailable"}","message":"Synthetic","retryable":${!permanent}}}""", if (permanent) 413 else 503) else json(snapshot()) }
            else -> json("{}", 404)
        }
    }
    private suspend fun fixture(selected: Boolean, watch: suspend (String, String, String, String) -> Unit = { _, _, _, _ -> }, outcomes: Boolean = true, block: suspend (NetworkMobileRepository, Fixture, MutableList<Int>) -> Unit) {
        val store = Store(); val fixture = Fixture().also { it.outcomes = outcomes }; val attempts = CopyOnWriteArrayList<Int>()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start()
            store.value = StoredState(host = PairedHost(HostEndpoint(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")), "device", "synthetic-device-token-for-tests", "Synthetic"), selectedSessionId = if (selected) "chat" else null)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = NetworkMobileRepository(store, scope, true, retryDelay = { attempts += it; 1L }, watchPrompt = watch)
            try { repo.setForeground(true); block(repo, fixture, attempts) }
            finally { repo.close(); scope.cancel() }
        }
    }
    @Test fun `N3 phone send persists its own request watch before HTTP admission`() = runBlocking {
        var watched: Triple<String, String, String>? = null
        fixture(true, { device, session, request, title -> assertEquals("Synthetic", title); watched = Triple(device, session, request) }) { repo, fixture, _ ->
            fixture.beforeMessage = { assertEquals("device", watched?.first); assertEquals("chat", watched?.second); assertTrue(validRequestId(watched!!.third)) }
            repo.sendMessage("Synthetic phone prompt")
            assertNotNull(watched)
        }
    }
    @Test fun capabilityFalseNeverRegistersWatchBeforePhoneAdmission() = runBlocking {
        var watches = 0
        fixture(true, { _, _, _, _ -> watches++ }, outcomes = false) { repo, fixture, _ ->
            var admitted = false
            fixture.beforeMessage = { admitted = true; assertEquals(0, watches) }
            repo.sendMessage("Synthetic phone prompt")
            assertTrue(admitted); assertEquals(0, watches)
        }
    }
    @Test fun `AND03 ordinary refresh retries a transient snapshot GET and recovers`() = runBlocking {
        fixture(true) { repo, fixture, attempts ->
            fixture.failures = 1
            val before = fixture.calls
            repo.refresh()
            withTimeout(3000) { repo.state.first { it.error == null && !it.busy && !it.snapshotStale && fixture.calls >= before + 2 } }
            assertEquals(before + 2, fixture.calls); assertEquals(listOf(0), attempts.toList())
        }
    }
    @Test fun `AND03 first open retries desired target without a display snapshot`() = runBlocking {
        fixture(false) { repo, fixture, attempts ->
            fixture.failures = 1; repo.selectSession("chat")
            withTimeout(3000) { repo.state.first { it.snapshot?.session?.id == "chat" && !it.snapshotStale && it.error == null } }
            assertEquals(2, fixture.calls); assertEquals(listOf(0), attempts.toList())
        }
    }
    @Test fun `AND03 projection retries are bounded at six`() = runBlocking {
        fixture(true) { repo, fixture, attempts ->
            fixture.failures = 100
            repo.refresh()
            // Seven real loopback requests plus CI scheduling/TLS setup are not a
            // three-second contract. Still require exactly the six retry attempts.
            withTimeout(15_000) { while (fixture.calls < 8) delay(5) }
            assertEquals(listOf(0, 1, 2, 3, 4, 5), attempts.toList())
            repo.leaveSession(); assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
        }
    }
    @Test fun `AND03 permanent snapshot error does not schedule retry`() = runBlocking {
        fixture(true) { repo, fixture, attempts ->
            fixture.failures = 1; fixture.permanent = true; repo.refresh()
            assertEquals("chat_unavailable", repo.state.value.error); assertTrue(attempts.isEmpty())
        }
    }
    @Test fun `AND01 broken optional history cannot prevent credential reset`() = runBlocking {
        val store = Store().apply { value = StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:9443"), "device", "synthetic-device-token-for-tests", "Synthetic")) }
        val history = object : HistoryStore {
            override val authority = HistoryAuthority()
            override suspend fun read() = HistoryRecord()
            override suspend fun write(value: HistoryRecord) {}
            override suspend fun clear() { throw java.security.KeyStoreException("Synthetic broken key store") }
        }
        val repo = NetworkMobileRepository(store, this, true, historyStore = history)
        try { repo.forget(); assertNull(store.value.host); assertEquals(1, store.resets); assertFalse(repo.state.value.paired) }
        finally { repo.close() }
    }
    @Test fun `AND01 credential deletion failure is not reported as successful forget`() = runBlocking {
        val store = Store().apply { value = StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:9443"), "device", "synthetic-device-token-for-tests", "Synthetic")); failReset = true }
        val repo = NetworkMobileRepository(store, this, true)
        try { repo.restore(); repo.forget(); assertNotNull(store.value.host); assertEquals("storage_failed", repo.state.value.error) }
        finally { repo.close() }
    }
}
