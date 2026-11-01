package dev.dshmobile.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.*

/** Regression tests for independently reproduced release blockers. */
class ReviewBlockerRegressionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val other = "00000000-0000-4000-8000-000000000002"
    @Test fun forbiddenSessionSettlesWithoutStoppingHealthyWatch() = runBlocking {
        var local = NotificationLocal().registerWatch("device", "forbidden-chat", id, 100)
            .registerWatch("device", "healthy-chat", other, 100)
        var healthyReads = 0; var posts = 0
        suspend fun update(f: (NotificationLocal) -> NotificationLocal) = f(local).also { local = it }
        val coordinator = WatchCoordinator({ local }, ::update, { session, ids, _, _ ->
            if (session == "forbidden-chat") throw MobileFailure("forbidden")
            healthyReads++
            PromptOutcomes("t", ids.map { PromptOutcome(it, if (healthyReads == 1) "running" else "completed", "2026-11-01T12:00:00Z", "turn") })
        }, { _, _ -> posts++ }, { 101 })
        WatchPollLoop(::update, coordinator, { true }, { true }, { 101 }).run()
        assertTrue(local.watches.isEmpty())
        assertEquals(2, healthyReads); assertEquals(1, posts)
    }
    @Test fun missingSessionAndForbiddenAreScopedInEitherRegistrationOrder() = runBlocking {
        for (failure in listOf("forbidden", "session_not_found")) for (reverse in listOf(false, true)) {
            var local = NotificationLocal()
            val groups = listOf("bad" to id, "healthy" to other).let { if (reverse) it.reversed() else it }
            groups.forEach { (session, request) -> local = local.registerWatch("device", session, request, 100) }
            var healthy = 0; var posts = 0
            suspend fun update(f: (NotificationLocal) -> NotificationLocal) = f(local).also { local = it }
            val c = WatchCoordinator({ local }, ::update, { session, ids, _, _ ->
                if (session == "bad") throw MobileFailure(failure)
                healthy++
                PromptOutcomes("t", ids.map { PromptOutcome(it, if (healthy == 1) "running" else "completed", "2026-11-01T12:00:00Z", "turn") })
            }, { _, _ -> posts++ }, { 101 })
            WatchPollLoop(::update, c, { true }, { true }, { 101 }).run()
            assertEquals(2, healthy); assertEquals(1, posts); assertTrue(local.watches.isEmpty())
        }
    }
    @Test fun globalFailureDominatesTransientRegardlessOfGroupOrder() = runBlocking {
        for (reverse in listOf(false, true)) {
            var local = NotificationLocal()
            listOf("auth" to id, "offline" to other).let { if (reverse) it.reversed() else it }
                .forEach { (session, request) -> local = local.registerWatch("device", session, request, 100) }
            var backoffs = 0
            suspend fun update(f: (NotificationLocal) -> NotificationLocal) = f(local).also { local = it }
            val c = WatchCoordinator({ local }, ::update, { session, _, _, _ ->
                throw MobileFailure(if (session == "auth") "revoked" else "network_unavailable")
            }, { _, _ -> fail("no post") }, { 101 })
            WatchPollLoop(::update, c, { true }, { true }, { 101 }, backoff = { _, _ -> backoffs++; throw CancellationException() }).run()
            assertEquals(0, backoffs); assertEquals(2, local.watches.size)
        }
    }
    @Test fun outcomeHttp404IsSessionScopedNotRetryableHostError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}"""))
            server.start()
            val api = HostApi(HostEndpoint(server.url("/").toString().removeSuffix("/")), "synthetic-test-token")
            try {
                val failure = runCatching { api.promptOutcomes("gone", listOf(id)) }.exceptionOrNull()
                assertEquals("session_not_found", (failure as MobileFailure).key)
            } finally { api.closeAndAwait() }
        }
    }
    private class Store : SecureStateStore {
        var value = StoredState()
        var failPending = false
        override suspend fun read() = value
        override suspend fun write(value: StoredState) {
            if (failPending && value.pending != null) throw java.io.IOException("Synthetic mandatory persist failure")
            this.value = value
        }
        override suspend fun clear() { value = StoredState() }
    }
    @Test fun rejectedReceiptReleasesAdmissionDespiteOptionalUnwatchFailure() = cleanupFailure("rejected", "command_rejected")
    @Test fun recoveryHoldKeepsProvenUnsentDespiteOptionalUnwatchFailure() = cleanupFailure("recovery_hold", "recovery_hold")
    @Test fun pendingPersistFailureIsNotMaskedByOptionalUnwatchFailure() = cleanupFailure("persist", "storage_failed")
    @Test fun preHeaderUnsentIsNotMaskedByOptionalUnwatchFailure() = cleanupFailure("preheader", "offline_no_send")
    private fun cleanupFailure(mode: String, expected: String) = runBlocking {
        val store = Store(); val messages = CopyOnWriteArrayList<String>(); var cleanupCalls = 0
        val snapshot = """{"session":{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true},"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}"""
        fun json(text: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(text)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false,"promptOutcomes":true}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> json("""{"items":[{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}],"nextCursor":null}""")
                    "/v1/sessions/chat" -> json(snapshot)
                    "/v1/sessions/chat/messages" -> {
                        val requestId = mobileJson.parseToJsonElement(request.body.readUtf8()).let { it as kotlinx.serialization.json.JsonObject }["requestId"].let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                        messages += requestId
                        if (mode == "recovery_hold") json("""{"error":{"code":"recovery_hold","message":"Synthetic","retryable":false}}""", 503)
                        else json("""{"requestId":"$requestId","status":"rejected","updatedAt":1}""", 400)
                    }
                    else -> if (request.requestUrl!!.encodedPath.startsWith("/v1/commands/")) json("""{"requestId":"${messages.last()}","status":"rejected","updatedAt":1}""") else json("{}", 404)
                }
            }
            server.start()
            store.value = StoredState(host = PairedHost(HostEndpoint(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")), "device", "synthetic-device-token-for-tests", "Synthetic"), selectedSessionId = "chat")
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = NetworkMobileRepository(store, scope, true, preHeaderRetryDelay = { 0 },
                unwatchPrompt = { cleanupCalls++; throw MobileFailure("storage_failed") })
            try {
                repo.setForeground(true)
                store.failPending = mode == "persist"
                if (mode == "preheader") server.shutdown()
                repo.sendMessage("Synthetic rejected task")
                val expectedPosts = if (mode in listOf("persist", "preheader")) 0 else 1
                assertEquals(expectedPosts, messages.size)
                assertNull(store.value.pending)
                assertEquals(expected, repo.state.value.error)
                assertEquals(1, cleanupCalls)
                if (mode == "rejected" || mode == "recovery_hold") {
                    repo.sendMessage("A second task")
                    assertEquals(expected, repo.state.value.error)
                    assertNull(store.value.pending)
                    assertEquals(2, messages.size)
                }
            } finally { repo.close(); scope.cancel() }
        }
    }
    @Test fun sessionFailureIsDroppedWhileTransientGroupRemainsRetryable() = runBlocking {
        var local = NotificationLocal().registerWatch("device", "forbidden-chat", id, 100)
            .registerWatch("device", "offline-chat", other, 100)
        var firstCalls = 0; var secondCalls = 0; var retries = 0
        suspend fun update(f: (NotificationLocal) -> NotificationLocal) = f(local).also { local = it }
        val c = WatchCoordinator({ local }, ::update, { session, _, _, _ ->
            if (session == "forbidden-chat") { firstCalls++; throw MobileFailure("forbidden") }
            secondCalls++; throw MobileFailure("network_unavailable")
        }, { _, _ -> fail("no alert") }, { 101 })
        val stop = runCatching {
            WatchPollLoop(::update, c, { true }, { true }, { 101 }, backoff = { _, _ -> retries++; throw CancellationException("stop after one observed retry") }).run()
        }.exceptionOrNull()
        assertTrue(stop is CancellationException)
        assertEquals(1, retries); assertEquals(1, firstCalls); assertEquals(1, secondCalls)
        assertEquals(listOf("offline-chat"), local.watches.map { it.sessionId })
    }
    @Test fun aliasesSurviveOutboxAckRestoreAndLaterMissingIdentity() {
        for (ackFirst in listOf(false, true)) {
            var state = NotificationLocal().registerWatch("device", "chat", id, 100)
            fun cut(request: String, status: String, turn: String?) = PromptOutcomes("t", listOf(PromptOutcome(request, status, "2026-11-01T12:00:00Z", turn)))
            state = state.reconcileWatches("chat", cut(id, "waiting", null), 101)
            if (ackFirst) state = state.acknowledgeWatchAlert(state.outbox.single().key, 102)
            state = state.reconcileWatches("chat", cut(id, "waiting", "turn"), 103)
            if (!ackFirst) { assertEquals(1, state.outbox.size); state = state.acknowledgeWatchAlert(state.outbox.single().key, 104) }
            state = mobileJson.decodeFromString(NotificationLocal.serializer(), mobileJson.encodeToString(NotificationLocal.serializer(), state))
            state = state.registerWatch("device", "chat", other, 105)
                .reconcileWatches("chat", cut(other, "waiting", "turn"), 106)
                .reconcileWatches("chat", cut(other, "waiting", null), 107)
            assertTrue(state.outbox.isEmpty())
            state = state.reconcileWatches("chat", cut(id, "running", null), 108)
                .reconcileWatches("chat", cut(other, "waiting", "turn"), 109)
            assertEquals(1, state.outbox.size) // Observed exit and return is a new episode.
            state = state.acknowledgeWatchAlert(state.outbox.single().key, 110)
                .reconcileWatches("chat", cut(id, "completed", null), 111)
                .reconcileWatches("chat", cut(other, "completed", "turn"), 112)
            assertEquals(1, state.outbox.size); assertEquals("answer", state.outbox.single().kind)
        }
    }
    @Test fun missingThenPresentTurnIdDoesNotRepeatUnchangedWaiting() {
        var local = NotificationLocal().registerWatch("device", "chat", id, 100).registerWatch("device", "chat", other, 100)
        local = local.reconcileWatches("chat", PromptOutcomes("t", listOf(
            PromptOutcome(id, "completed", "2026-11-01T12:00:00Z"), PromptOutcome(other, "completed", "2026-11-01T12:00:00Z"))), 101)
        assertEquals(2, local.outbox.size)
        var waiting = NotificationLocal().registerWatch("device", "chat", id, 100)
            .reconcileWatches("chat", PromptOutcomes("t", listOf(PromptOutcome(id, "waiting", "2026-11-01T12:00:00Z"))), 101)
        waiting = waiting.acknowledgeWatchAlert(waiting.outbox.single().key, 102)
            .reconcileWatches("chat", PromptOutcomes("t2", listOf(PromptOutcome(id, "waiting", "2026-11-01T12:00:00Z", "turn"))), 103)
        assertTrue("same request must not alert again when optional turnId appears", waiting.outbox.isEmpty())
    }
}
