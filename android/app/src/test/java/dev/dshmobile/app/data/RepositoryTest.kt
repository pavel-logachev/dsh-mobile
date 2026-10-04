package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import okhttp3.tls.HeldCertificate
import okhttp3.CertificatePinner
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RepositoryTest {
    /** Models the durable device boundary, not an internal network/reducer mock. */
    private class MemoryStore : SecureStateStore {
        var value = StoredState()
        var gate: CompletableDeferred<Unit>? = null
        var failWrites = false
        override suspend fun read() = value
        override suspend fun write(value: StoredState) {
            if (failWrites) throw java.io.IOException("Synthetic storage failure")
            gate?.await(); this.value = value
        }
        override suspend fun clear() { value = StoredState() }
    }
    private class Fixture : Dispatcher() {
        val mutations = CopyOnWriteArrayList<RecordedRequest>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var index = """{"items":[${session()}],"nextCursor":null}"""
        var capabilities = """{"protocolVersion":1,"hostName":"Synthetic host","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}"""
        var otherSnapshot: (() -> MockResponse)? = null
        var create: ((RecordedRequest) -> MockResponse)? = null
        var failIndex = false
        var events = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: snapshot\ndata: ${snapshot()}\n\n") }
        var snapshotText = snapshot()
        var message: (RecordedRequest) -> MockResponse = { json("""{"requestId":"${requestId(it)}","status":"uncertain","updatedAt":20}""", 202) }
        var receipt: (String) -> MockResponse = { json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""", 404) }
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            return when (request.requestUrl?.encodedPath) {
            "/v1/pairings" -> json("""{"deviceId":"device-synthetic","deviceToken":"synthetic-device-token-for-tests","hostName":"Synthetic host","protocolVersion":1}""", 201)
            "/v1/capabilities" -> json(capabilities)
            "/v1/workspaces" -> json("""{"items":[{"id":"workspace","name":"Synthetic workspace","canExecute":true}]}""")
            "/v1/presets" -> json("""{"items":[]}""")
            "/v1/sessions" -> if (request.method == "POST") { mutations += request; create?.invoke(request) ?: json("{}", 400) }
                else if (failIndex) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json(index)
            "/v1/sessions/other" -> otherSnapshot?.invoke() ?: json(snapshot().replace("\"id\":\"chat\"", "\"id\":\"other\""))
            "/v1/sessions/chat" -> json(snapshotText)
            "/v1/sessions/chat/events" -> events()
            "/v1/sessions/chat/messages" -> { mutations += request; message(request) }
            "/v1/sessions/chat/cancellations" -> { mutations += request; message(request) }
            else -> if (request.requestUrl?.encodedPath?.startsWith("/v1/commands/") == true)
                receipt(request.requestUrl!!.pathSegments.last()) else json("{}", 404)
            }
        }
    }
    @Test fun `a durable command exists before POST and canonical request ID reconciles it once`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.message = { request ->
                val id = requestId(request)
                assertEquals(id, store.value.pending!!.requestId)
                assertEquals("Exact synthetic text", store.value.pending!!.text)
                assertEquals("Exact synthetic text", store.value.drafts["chat"])
                assertEquals("Bearer synthetic-device-token-for-tests", request.getHeader("Authorization"))
                fixture.snapshotText = snapshot("""[{"id":"user","role":"user","text":"Exact synthetic text","createdAt":20,"requestId":"$id"}]""")
                json("""{"requestId":"$id","status":"accepted","updatedAt":20}""")
            }
            repo.sendMessage("Exact synthetic text")
            assertNull(repo.state.value.pending)
            assertEquals("", repo.state.value.draft)
            assertEquals(listOf("user"), repo.state.value.snapshot!!.messages.map { it.id })
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `lost mutation response queries its original ID and never resends even after recreation`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.message = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            repo.sendMessage("Synthetic lost response")
            val id = repo.state.value.pending!!.requestId
            assertEquals("uncertain", repo.state.value.pending!!.status)
            assertEquals(id, store.value.pending!!.requestId)
            assertTrue(fixture.requests.any { it.path == "/v1/commands/$id" })
            repo.refresh()
            repo.sendMessage("Would duplicate")
            assertEquals("command_unresolved", repo.state.value.error)
            repo.setForeground(false)
            repo.close()
            val recreated = NetworkMobileRepository(store, scope, true)
            try {
                recreated.restore()
                assertEquals(id, recreated.state.value.pending!!.requestId)
                recreated.setForeground(true)
                assertEquals(id, recreated.state.value.pending!!.requestId)
                assertEquals(1, fixture.mutations.size)
                fixture.receipt = { requested ->
                    fixture.snapshotText = snapshot("""[{"id":"user","role":"user","text":"Synthetic lost response","createdAt":20,"requestId":"$requested"}]""")
                    json("""{"requestId":"$requested","status":"accepted","updatedAt":30}""")
                }
                recreated.resolvePending()
                assertNull(recreated.state.value.pending)
                assertEquals(1, fixture.mutations.size)
            } finally { recreated.close() }
        }
    }
    @Test fun `latest background intent wins queued stop start stop while a cancelled mutation holds admission`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            val requested = CompletableDeferred<Unit>()
            val releaseResponse = java.util.concurrent.CountDownLatch(1)
            fixture.message = { requested.complete(Unit); releaseResponse.await(5, TimeUnit.SECONDS); json("{}", 500) }
            val send = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.sendMessage("Synthetic held mutation") }
            requested.await()
            store.gate = CompletableDeferred()
            val before = fixture.requests.size
            val stopped = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false) }
            val staleStarted = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(true) }
            val latestStopped = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false) }
            store.gate!!.complete(Unit)
            try {
                send.await(); stopped.await(); staleStarted.await(); latestStopped.await()
                assertEquals(dev.dshmobile.app.model.ConnectionState.OFFLINE, repo.state.value.connection)
                assertEquals("uncertain", store.value.pending!!.status)
                assertEquals("Synthetic held mutation", store.value.pending!!.text)
                assertEquals("No stale foreground GET or new transport after the latest STOP", before, fixture.requests.size)
                assertEquals(1, fixture.mutations.size)
            } finally { releaseResponse.countDown() }
        }
    }
    @Test fun `failed durable write sends no mutation and offline draft is never queued`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            store.failWrites = true
            repo.sendMessage("Synthetic")
            assertEquals("storage_failed", repo.state.value.error)
            assertTrue(fixture.mutations.isEmpty())
            store.failWrites = false
            repo.setForeground(false)
            repo.sendMessage("Still offline")
            assertEquals("offline_no_send", repo.state.value.error)
            repo.setForeground(true)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `local abandonment only removes its own send draft without any network action`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.sendMessage("Uncertain synthetic")
            val count = fixture.requests.size
            repo.abandonPending()
            assertNull(repo.state.value.pending)
            assertEquals("", repo.state.value.draft)
            assertNull(store.value.pending)
            assertEquals(count, fixture.requests.size)
        }
    }
    @Test fun `cancel persists expected cursor and accepted receipt waits for authoritative stopped state`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.snapshotText = snapshot(running = true, cursor = 7)
            repo.refresh()
            fixture.message = { request ->
                val body = mobileJson.parseToJsonElement(request.body.clone().readUtf8()) as JsonObject
                assertEquals("7", (body["expectedCursor"] as JsonPrimitive).content)
                assertEquals(7L, store.value.pending!!.expectedCursor)
                json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""")
            }
            repo.cancelRun()
            val id = repo.state.value.pending!!.requestId
            assertEquals("accepted", repo.state.value.pending!!.status)
            assertTrue(repo.state.value.snapshot!!.session.running)
            fixture.receipt = { json("""{"requestId":"$it","status":"accepted","updatedAt":30}""") }
            fixture.snapshotText = snapshot(running = false, cursor = 8)
            repo.resolvePending()
            assertNull(repo.state.value.pending)
            assertFalse(repo.state.value.snapshot!!.session.running)
            assertEquals(id, requestId(fixture.mutations.single()))
        }
    }
    @Test fun `server-driven false capability and unknown activity fail closed`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"textPrompt\":true", "\"textPrompt\":false")
            repo.refresh(); repo.sendMessage("Synthetic")
            assertEquals("unsupported", repo.state.value.error)
            assertTrue(fixture.mutations.isEmpty())
            fixture.snapshotText = snapshot().replace("\"idle\"", "\"future-state\"")
            repo.refresh()
            assertEquals("unknown", repo.state.value.snapshot!!.activity)
            assertFalse(repo.state.value.snapshot!!.session.canExecute)
        }
    }
    @Test fun `accepted create stays resolved if followup index response is lost`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.create = { request ->
                fixture.failIndex = true
                json("""{"requestId":"${requestId(request)}","status":"accepted","result":{"sessionId":"other"},"updatedAt":30}""", 201)
            }
            repo.createSession("workspace")
            assertNull(store.value.pending)
            assertNull(repo.state.value.pending)
            fixture.failIndex = false
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            assertNull(repo.state.value.pending)
            assertEquals("other", repo.state.value.snapshot!!.session.id)
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `queued text send cannot target a different chat after slow navigation`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            val started = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.otherSnapshot = {
                started.complete(Unit)
                release.await()
                json(snapshot().replace("\"id\":\"chat\"", "\"id\":\"other\""))
            }
            val navigation = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.selectSession("other") }
            started.await()
            val edit = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.updateDraft("Draft still belongs to chat") }
            val send = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.sendMessage("Old chat prompt") }
            release.countDown()
            navigation.await(); edit.await(); send.await()
            assertTrue(fixture.mutations.isEmpty())
            assertEquals("other", repo.state.value.snapshot!!.session.id)
            assertEquals("", repo.state.value.draft)
            fixture.otherSnapshot = null
            repo.selectSession("chat")
            assertEquals("Draft still belongs to chat", repo.state.value.draft)
        }
    }
    @Test fun `empty session cursor minus one remains authoritative`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(cursor = -1)
            repo.refresh()
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertEquals(-1L, repo.state.value.snapshot!!.cursor)
        }
    }
    @Test fun `repeated failed observer opens have only six bounded scheduled reconnects`() = runBlocking {
        val store = MemoryStore()
        val fixture = Fixture().apply {
            capabilities = capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            events = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        }
        val attempts = CopyOnWriteArrayList<Int>()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start()
            val repo = NetworkMobileRepository(store, this, true, retryDelay = { attempt -> attempts += attempt; 1L })
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic phone")
                repo.selectSession("chat")
                withTimeout(5_000) { repo.state.first { attempts.size >= 6 && it.connection == ConnectionState.OFFLINE } }
                assertEquals(listOf(0, 1, 2, 3, 4, 5), attempts.take(6))
                val seventh = kotlinx.coroutines.withTimeoutOrNull(150) { repo.state.first { attempts.size > 6 } }
                assertNull(seventh)
                assertEquals(7, fixture.requests.count { it.path == "/v1/sessions/chat/events" })
            } finally { repo.close() }
        }
    }
    @Test fun `expired stored trust stays resettable locally without sending a credential`() = runBlocking {
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
        val now = System.currentTimeMillis()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").validityInterval(now - 20_000, now - 10_000).build()
        val store = MemoryStore().apply {
            value = StoredState(host = PairedHost(HostEndpoint("https://localhost:9443", CertificatePinner.pin(certificate.certificate), certificate.certificatePem()), "synthetic-device", "synthetic-device-token-for-tests", "Synthetic host"))
        }
        val repo = NetworkMobileRepository(store, scope, true)
        try {
            repo.restore()
            assertTrue(repo.state.value.paired)
            assertEquals("certificate_invalid", repo.state.value.error)
            repo.forget()
            assertFalse(repo.state.value.paired)
            assertNull(store.value.host)
        } finally { repo.close(); scope.cancel() }
    }
    @Test fun `background closes observation only and foreground replaces with new authoritative snapshot`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            val initial = "event: snapshot\ndata: ${snapshot("""[{"id":"first","role":"assistant","text":"First","createdAt":10}]""")}\n\n"
            fixture.events = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody(initial + ": later\n\n").throttleBody(initial.toByteArray().size.toLong(), 1, TimeUnit.SECONDS) }
            repo.refresh()
            withTimeout(5_000) { repo.state.first { it.snapshot?.messages?.singleOrNull()?.id == "first" } }
            repo.setForeground(false)
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            fixture.snapshotText = snapshot("""[{"id":"replacement","role":"assistant","text":"Replacement","createdAt":20}]""")
            fixture.events = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: snapshot\ndata: ${fixture.snapshotText}\n\n") }
            repo.setForeground(true)
            assertEquals(listOf("replacement"), repo.state.value.snapshot!!.messages.map { it.id })
            assertTrue(fixture.mutations.isEmpty())
            assertTrue(fixture.requests.none { it.path == "/v1/device" })
        }
    }
    @Test fun `burst edits remain immediate and restore the last complete draft`() = runBlocking {
        withRepository { repo, store, _, scope ->
            store.gate = CompletableDeferred()
            val edits = listOf("N", "Ne", "New", "New text").map { text ->
                scope.async(start = CoroutineStart.UNDISPATCHED) { repo.updateDraft(text) }
            }
            assertEquals("New text", repo.state.value.draft)
            store.gate!!.complete(Unit)
            edits.forEach { it.await() }
            repo.close()
            val recreated = NetworkMobileRepository(store, scope, true)
            try { recreated.restore(); assertEquals("New text", recreated.state.value.draft) }
            finally { recreated.close() }
        }
    }
    @Test fun `editing publishes the exact latest draft while secure disk is slow`() = runBlocking {
        withRepository { repo, store, _, scope ->
            store.gate = CompletableDeferred()
            val edit = scope.async { repo.updateDraft("New text") }
            // Wait until coroutine gets to the suspended disk write without sleeping.
            kotlinx.coroutines.yield()
            assertEquals("New text", repo.state.value.draft)
            store.gate!!.complete(Unit)
            edit.await()
        }
    }
    private suspend fun withRepository(block: suspend (NetworkMobileRepository, MemoryStore, Fixture, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
        val store = MemoryStore()
        val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture
            server.start()
            val repo = NetworkMobileRepository(store, scope, debug = true)
            try {
                repo.setForeground(true)
                // Bind the URL to this fixture's IPv4 listener, not localhost's unrelated IPv6 route.
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic phone")
                repo.selectSession("chat")
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                block(repo, store, fixture, scope)
            } finally { repo.close(); scope.cancel() }
        }
    }
    companion object {
        private fun session(running: Boolean = false) = """{"id":"chat","title":"Synthetic chat","workspaceId":"workspace","updatedAt":10,"running":$running,"canExecute":true}"""
        private fun snapshot(messages: String = "[]", running: Boolean = false, cursor: Long = 1) =
            """{"session":${session(running)},"messages":$messages,"cursor":$cursor,"hasMore":false,"activity":"${if (running) "running" else "idle"}"}"""
        private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        private fun requestId(request: RecordedRequest): String = (mobileJson.parseToJsonElement(request.body.clone().readUtf8()) as kotlinx.serialization.json.JsonObject)["requestId"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }
}
