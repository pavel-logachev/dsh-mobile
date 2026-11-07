package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        @Volatile var failClear = false
        @Volatile var resetCalls = 0
        override suspend fun read() = value
        override suspend fun write(value: StoredState) {
            if (failWrites) throw java.io.IOException("Synthetic storage failure")
            gate?.await(); this.value = value
        }
        override suspend fun clear() {
            if (failClear) throw java.io.IOException("Synthetic clear failure")
            value = StoredState()
        }
        override suspend fun reset() { resetCalls++; value = StoredState() }
    }
    private class Fixture : Dispatcher() {
        val mutations = CopyOnWriteArrayList<RecordedRequest>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var index = """{"items":[${session()}],"nextCursor":null}"""
        var capabilities = """{"protocolVersion":1,"hostName":"Synthetic host","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}"""
        var otherSnapshot: (() -> MockResponse)? = null
        var chatSnapshot: (() -> MockResponse)? = null
        var create: ((RecordedRequest) -> MockResponse)? = null
        val deletedUploads = java.util.concurrent.CopyOnWriteArrayList<String>()
        var uploadCount = 0
        var failIndex = false
        var sessionPage: ((RecordedRequest) -> MockResponse)? = null
        var stopServer: () -> Int = { 0 }
        var metadataDelayMs = 0L
        var metadataGate: (() -> Unit)? = null
        var events = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: snapshot\ndata: ${snapshot()}\n\n") }
        var snapshotText = snapshot()
        var message: (RecordedRequest) -> MockResponse = { json("""{"requestId":"${requestId(it)}","status":"uncertain","updatedAt":20}""", 202) }
        var receipt: (String) -> MockResponse = { json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""", 404) }
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            if (request.method == "DELETE" && request.requestUrl!!.encodedPath.startsWith("/v1/sessions/chat/uploads/")) {
                deletedUploads += request.requestUrl!!.encodedPath.substringAfterLast('/')
                return MockResponse().setResponseCode(404)
            }
            if (request.requestUrl?.encodedPath in setOf("/v1/capabilities", "/v1/workspaces", "/v1/presets")) metadataGate?.invoke()
            return when (request.requestUrl?.encodedPath) {
            "/v1/pairings" -> json("""{"deviceId":"device-synthetic","deviceToken":"synthetic-device-token-for-tests","hostName":"Synthetic host","protocolVersion":1}""", 201)
            "/v1/capabilities" -> json(capabilities).setBodyDelay(metadataDelayMs, TimeUnit.MILLISECONDS)
            "/v1/workspaces" -> json("""{"items":[{"id":"workspace","name":"Synthetic workspace","canExecute":true}]}""").setBodyDelay(metadataDelayMs, TimeUnit.MILLISECONDS)
            "/v1/presets" -> json("""{"items":[]}""").setBodyDelay(metadataDelayMs, TimeUnit.MILLISECONDS)
            "/v1/sessions" -> if (request.method == "POST") { mutations += request; create?.invoke(request) ?: json("{}", 400) }
                else if (failIndex) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else sessionPage?.invoke(request) ?: json(index)
            "/v1/sessions/other" -> otherSnapshot?.invoke() ?: json(snapshot().replace("\"id\":\"chat\"", "\"id\":\"other\""))
            "/v1/sessions/chat" -> chatSnapshot?.invoke() ?: json(snapshotText)
            "/v1/sessions/chat/uploads" -> { uploadCount++; json("""{"uploadId":"fresh-$uploadCount","expiresAt":9999999999999,"attachment":{"id":"ref","name":"test.txt","size":4,"mime":"text/plain","kind":"file"}}""", 201) }
            "/v1/sessions/chat/events" -> events()
            "/v1/sessions/chat/messages", "/v1/sessions/other/messages" -> { mutations += request; message(request) }
            "/v1/sessions/chat/cancellations", "/v1/sessions/chat/queue", "/v1/sessions/chat/title", "/v1/sessions/chat/model", "/v1/sessions/chat/answers" -> { mutations += request; message(request) }
            else -> if (request.requestUrl?.encodedPath?.startsWith("/v1/commands/") == true)
                receipt(request.requestUrl!!.pathSegments.last()) else json("{}", 404)
            }
        }
    }
    @Test fun `late stop from previous activity cannot take visible activity offline or suppress manual reconnect`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.setForeground(false)
            val previous = Any(); val visible = Any()
            repo.setForeground(true, previous)
            repo.setForeground(true, visible)
            repo.setForeground(false, previous)
            repo.setForeground(false, previous) // Disposal after ON_STOP is idempotent.
            assertEquals("The visible owner still authorizes networking", ConnectionState.ONLINE, repo.state.value.connection)
            val before = fixture.requests.count { it.path == "/v1/capabilities" }
            withTimeout(1000) { repo.refresh() }
            assertEquals(before + 1, fixture.requests.count { it.path == "/v1/capabilities" })
            repo.setForeground(false, visible)
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            val stopped = fixture.requests.size
            repo.refresh()
            assertEquals("No reads with zero foreground owners", stopped, fixture.requests.size)
            repo.setForeground(true, visible)
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertTrue(fixture.mutations.isEmpty())
        }
    }

    @Test fun `home roster refreshes flags without manual refresh and stops on background or chat`() = runBlocking {
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
        val fixture = Fixture(); val store = MemoryStore()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start()
            val repo = NetworkMobileRepository(store, scope, debug = true, observeRestoredSession = false, prefetchEnabled = false, indexRefreshIntervalMs = 100)
            suspend fun awaitFlag(running: Boolean) = kotlinx.coroutines.withTimeout(5000) {
                while (repo.state.value.sessions.singleOrNull()?.running != running) kotlinx.coroutines.delay(20)
            }
            fun lists() = fixture.requests.count { it.requestUrl?.encodedPath == "/v1/sessions" }
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic")
                fixture.index = """{"items":[${session(true)}],"nextCursor":null}"""
                awaitFlag(true)
                fixture.index = """{"items":[${session(false)}],"nextCursor":null}"""
                awaitFlag(false)
                repo.setForeground(false)
                val paused = lists(); kotlinx.coroutines.delay(250); assertEquals(paused, lists())
                repo.setForeground(true); repo.selectSession("chat")
                val selected = lists(); kotlinx.coroutines.delay(250); assertEquals(selected, lists())
                repo.leaveSession(); fixture.index = """{"items":[${session(true)}],"nextCursor":null}"""
                awaitFlag(true)
                assertTrue(fixture.mutations.isEmpty())
            } finally { repo.close(); scope.cancel() }
        }
    }

    @Test fun `manual reconnect replaces self retired read transport without replaying uncertain prompt`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.sendMessage("Synthetic uncertain intent")
            assertEquals(1, fixture.mutations.size)
            assertNotNull(store.value.pending)
            // Fault injection at the transport boundary: model a blocked cleanup + eight queued
            // cancellations. The next real SSE cancel must retire HostApi through cancelOnIo.
            val apiField = repo.javaClass.getDeclaredField("api").apply { isAccessible = true }
            val transport = apiField.get(repo) as HostApi
            val ioField = transport.javaClass.getDeclaredField("io").apply { isAccessible = true }
            val io = ioField.get(transport) as TransportIoOwner
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            assertTrue(io.submit { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            try {
                repeat(8) { assertTrue(io.submit {}) }
                val source = transport.observe("chat", {}, {})
                source.cancel() // Overflow invokes actual HostApi.close(), not a fake network.
                try { transport.capabilities(); fail("Overflow must retire the read transport") }
                catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
                // Replacement must not wait for old physical cleanup, and that late cleanup
                // must not cancel the replacement's independent pool/requests.
                repo.refresh()
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                assertFalse((apiField.get(repo) as HostApi).isRetired)
                assertNotSame(transport, apiField.get(repo))
            } finally { release.countDown(); transport.awaitClosed() }
            repo.refresh()
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertEquals("chat", repo.state.value.snapshot?.session?.id)
            assertNotNull(store.value.pending)
            assertEquals("Reconnect must only reconcile the original uncertain request", 1, fixture.mutations.size)
            assertTrue(fixture.requests.any { it.path?.startsWith("/v1/commands/") == true })
        }
    }
    @Test fun `network restored interrupts exhausted backoff without replay and coalesces flaps`() = runBlocking {
        val store = MemoryStore()
        val fixture = Fixture()
        val attempts = CopyOnWriteArrayList<Int>()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start()
            val repo = NetworkMobileRepository(store, this, true, retryDelay = { attempts += it; 1L })
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic")
                fixture.failIndex = true; repo.refresh()
                withTimeout(5000) { repo.state.first { attempts.size == 6 && it.connection == ConnectionState.OFFLINE } }
                // Wait for the last scheduled attempt to finish, not just its scheduling.
                withTimeout(5000) { while (fixture.requests.count { it.path?.startsWith("/v1/sessions?") == true } < 8) kotlinx.coroutines.delay(1) }
                fixture.failIndex = false
                val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
                fixture.metadataGate = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
                val before = fixture.requests.count { it.path == "/v1/capabilities" }
                val wake = repo.javaClass.methods.find { it.name.startsWith("networkRestored") && it.parameterCount == 0 }
                assertNotNull("Repository must accept a restored-network wake", wake)
                wake!!.invoke(repo)
                try {
                    withTimeout(1000) { entered.await() }
                    repeat(20) { wake.invoke(repo) }
                } finally { release.countDown() }
                withTimeout(5000) { repo.state.first { it.connection == ConnectionState.ONLINE } }
                assertEquals(before + 1, fixture.requests.count { it.path == "/v1/capabilities" })
                assertTrue(fixture.mutations.isEmpty())
            } finally { repo.close() }
        }
    }
    @Test fun `manual reconnect after exhausted retries rearms recovery without replaying uncertain send`() = runBlocking {
        val fixture = Fixture()
        val store = MemoryStore()
        val attempts = CopyOnWriteArrayList<Int>()
        MockWebServer().use { server ->
            server.dispatcher = fixture; server.start()
            val repo = NetworkMobileRepository(store, this, true, retryDelay = { attempts += it; 100L })
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic")
                repo.selectSession("chat")
                repo.sendMessage("Synthetic uncertain intent")
                // Use an explicit 503 so each failed refresh has one index GET, not EOF recovery.
                val indexReads = fixture.requests.count { it.path?.startsWith("/v1/sessions?") == true }
                fixture.sessionPage = { json("""{"error":{"code":"unavailable","message":"Synthetic","retryable":true}}""", 503) }
                repo.refresh()
                // Initial refresh plus all six failed scheduled refreshes have finished.
                withTimeout(5000) {
                    while (fixture.requests.count { it.path?.startsWith("/v1/sessions?") == true } < indexReads + 7 ||
                        repo.state.value.busy) kotlinx.coroutines.delay(1)
                }
                assertEquals(listOf(0, 1, 2, 3, 4, 5), attempts.toList())
                assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
                // The explicit tap can itself fail just before the network becomes usable.
                repo.refresh()
                fixture.sessionPage = null
                withTimeout(1500) { while (attempts.size < 7) kotlinx.coroutines.delay(1) }
                assertEquals("An explicit reconnect starts a fresh bounded retry budget", 0, attempts[6])
                withTimeout(5000) { repo.state.first { it.connection == ConnectionState.ONLINE && !it.busy } }
                // ONLINE is published before receipt reconciliation completes.
                withTimeout(5000) { while (fixture.requests.none { it.path?.startsWith("/v1/commands/") == true }) kotlinx.coroutines.delay(1) }
                assertEquals(1, fixture.mutations.size)
                assertNotNull(store.value.pending)
                assertTrue(fixture.requests.any { it.path?.startsWith("/v1/commands/") == true })
            } finally { repo.close() }
        }
    }
    @Test fun `first authorized page is online while remaining pages load and cached selection survives`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            repo.leaveSession()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.sessionPage = { request ->
                if (request.requestUrl!!.queryParameter("cursor") == null)
                    json("""{"items":[${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":"next"}""")
                else { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); json("""{"items":[${session()}],"nextCursor":null}""") }
            }
            val refresh = scope.async { repo.refresh() }
            try {
                withTimeout(5000) { entered.await() }
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                // An incomplete index merges, rather than erasing already authorized entries.
                assertEquals(listOf("other", "chat"), repo.state.value.sessions.map { it.id })
                assertTrue(repo.state.value.sessionsTruncated)
                withTimeout(750) { refresh.await() }
                val cachedRead = CompletableDeferred<Unit>(); val releaseChat = java.util.concurrent.CountDownLatch(1)
                fixture.chatSnapshot = { cachedRead.complete(Unit); releaseChat.await(5, TimeUnit.SECONDS); json(snapshot()) }
                val selection = scope.async { repo.selectSession("chat") }
                try {
                    withTimeout(1000) { cachedRead.await() }
                    assertEquals("chat", repo.state.value.snapshot?.session?.id)
                    assertTrue(repo.state.value.snapshotStale)
                    assertTrue(repo.state.value.busy)
                } finally { releaseChat.countDown(); selection.await(); fixture.chatSnapshot = null }
            } finally { release.countDown() }
            withTimeout(5000) { repo.state.first { !it.sessionsTruncated && it.sessions.size == 2 } }
            repo.selectSession("chat")
            assertNotNull(repo.state.value.snapshot)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `chat open uses one SSE snapshot and no preceding GET`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            repo.leaveSession()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            repo.refresh()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            val initial = "event: snapshot\ndata: ${snapshot(cursor = 7)}\n\n"
            fixture.events = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS)
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(initial + ": held\n\n")
                    .throttleBody(initial.toByteArray().size.toLong(), 1, TimeUnit.SECONDS) }
            val gets = fixture.requests.count { it.path == "/v1/sessions/chat" }
            val open = scope.async { repo.selectSession("chat") }
            try {
                withTimeout(5000) { entered.await() }
                assertNotNull(repo.state.value.snapshot)
                assertTrue(repo.state.value.snapshotStale)
                assertEquals(gets, fixture.requests.count { it.path == "/v1/sessions/chat" })
            } finally { release.countDown() }
            open.await()
            assertEquals(7L, repo.state.value.snapshot?.cursor)
            assertFalse(repo.state.value.snapshotStale)
            assertEquals(gets, fixture.requests.count { it.path == "/v1/sessions/chat" })
            repo.leaveSession()
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `stream first preheaders failure falls back once to GET without mutation`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.leaveSession()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            repo.refresh()
            var opens = 0
            fixture.events = { opens++; if (opens == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: snapshot\ndata: ${snapshot(cursor = 8)}\n\n") }
            val gets = fixture.requests.count { it.path == "/v1/sessions/chat" }
            repo.selectSession("chat")
            assertNotNull(repo.state.value.snapshot)
            assertEquals(gets + 1, fixture.requests.count { it.path == "/v1/sessions/chat" })
            repo.leaveSession()
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `stream auth rejection cannot be overruled by successful fallback GET`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.leaveSession()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            repo.refresh()
            fixture.events = { json("""{"error":{"code":"unauthorized","message":"Synthetic","retryable":false}}""", 401) }
            val gets = fixture.requests.count { it.path == "/v1/sessions/chat" }
            repo.selectSession("chat")
            assertEquals(gets + 1, fixture.requests.count { it.path == "/v1/sessions/chat" })
            assertEquals(ConnectionState.REVOKED, repo.state.value.connection)
            assertNull(repo.state.value.snapshot)
            assertTrue(repo.state.value.sessions.isEmpty())
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `leaving pending first SSE snapshot cancels selection without GET fallback`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            repo.leaveSession()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            repo.refresh()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.events = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS)
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": heartbeat\n\n") }
            val gets = fixture.requests.count { it.path == "/v1/sessions/chat" }
            val selection = scope.async { repo.selectSession("chat") }
            try {
                withTimeout(5000) { entered.await() }
                withTimeout(750) { repo.leaveSession(); selection.await() }
                assertFalse(repo.state.value.observingSession)
                assertFalse(repo.state.value.busy)
                assertEquals(gets, fixture.requests.count { it.path == "/v1/sessions/chat" })
                assertTrue(fixture.mutations.isEmpty())
            } finally { release.countDown() }
        }
    }
    @Test fun `revocation during background paging clears first page and cached history`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            repo.leaveSession()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.sessionPage = { request ->
                if (request.requestUrl!!.queryParameter("cursor") == null) json("""{"items":[${session()}],"nextCursor":"next"}""")
                else { entered.complete(Unit); release.await(5, TimeUnit.SECONDS)
                    json("""{"error":{"code":"revoked","message":"Synthetic","retryable":false}}""", 401) }
            }
            repo.refresh()
            try {
                withTimeout(5000) { entered.await() }
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            } finally { release.countDown() }
            withTimeout(5000) { repo.state.first { it.connection == ConnectionState.REVOKED } }
            assertTrue(repo.state.value.sessions.isEmpty())
            assertNull(repo.state.value.snapshot)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `stream first timeout falls back to GET and background cancels network wake`() = runBlocking {
        withRepository { original, store, fixture, scope ->
            original.close()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            var listener: (() -> Unit)? = null; var registrations = 0; var removals = 0
            val repo = NetworkMobileRepository(store, scope, true, observeRestoredSession = false, firstSnapshotTimeoutMs = 50,
                networkWake = { callback -> registrations++; listener = callback; { removals++ } })
            try {
                repo.setForeground(true)
                fixture.events = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": no snapshot\n\n").setHeadersDelay(500, TimeUnit.MILLISECONDS) }
                val gets = fixture.requests.count { it.path == "/v1/sessions/chat" }
                repo.selectSession("chat")
                assertEquals(gets + 1, fixture.requests.count { it.path == "/v1/sessions/chat" })
                assertFalse(repo.state.value.snapshotStale)
                withTimeout(1000) { while (fixture.requests.count { it.path == "/v1/sessions/chat/events" } < 2) kotlinx.coroutines.delay(1) }
                repo.setForeground(false)
                val before = fixture.requests.count { it.path == "/v1/capabilities" }
                listener!!()
                kotlinx.coroutines.yield()
                assertEquals(before, fixture.requests.count { it.path == "/v1/capabilities" })
                assertEquals(1, registrations); assertEquals(1, removals)
                assertTrue(fixture.mutations.isEmpty())
            } finally { repo.close() }
        }
    }
    @Test fun `restored network cancels a long pending backoff and flaps do not queue refresh`() = runBlocking {
        withRepository { original, store, fixture, scope ->
            original.close()
            val scheduled = CompletableDeferred<Unit>()
            val repo = NetworkMobileRepository(store, scope, true, observeRestoredSession = false,
                retryDelay = { scheduled.complete(Unit); 30_000L })
            try {
                repo.setForeground(true)
                fixture.failIndex = true; repo.refresh()
                withTimeout(1000) { scheduled.await() }
                fixture.failIndex = false
                val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
                fixture.metadataGate = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
                val before = fixture.requests.count { it.path == "/v1/capabilities" }
                repo.networkRestored()
                try {
                    withTimeout(1000) { entered.await() }
                    repeat(20) { repo.networkRestored() }
                } finally { release.countDown() }
                withTimeout(5000) { repo.state.first { it.connection == ConnectionState.ONLINE && !it.busy } }
                repo.networkRestored(); kotlinx.coroutines.yield()
                assertEquals(before + 1, fixture.requests.count { it.path == "/v1/capabilities" })
                assertTrue(fixture.mutations.isEmpty())
            } finally { repo.close() }
        }
    }
    @Test fun `background during first SSE wait releases navigation and metadata admission`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            repo.leaveSession()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            repo.refresh()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.events = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS)
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": heartbeat\n\n") }
            val selection = scope.async { repo.selectSession("chat") }
            try {
                withTimeout(5000) { entered.await() }
                withTimeout(750) { repo.setForeground(false); selection.await() }
                assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
                assertFalse(repo.state.value.busy)
                assertTrue(fixture.mutations.isEmpty())
            } finally { release.countDown() }
        }
    }
    @Test fun `new refresh cancels old background page without publishing late entries`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.leaveSession()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            val oldPage = session().replace("\"id\":\"chat\"", "\"id\":\"obsolete\"")
            fixture.sessionPage = { request ->
                if (request.requestUrl!!.queryParameter("cursor") == null) json("""{"items":[${session()}],"nextCursor":"next"}""")
                else { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); json("""{"items":[$oldPage],"nextCursor":null}""") }
            }
            repo.refresh()
            try {
                withTimeout(5000) { entered.await() }
                fixture.sessionPage = null
                withTimeout(1000) { repo.refresh() }
                assertEquals(listOf("chat"), repo.state.value.sessions.map { it.id })
                assertFalse(repo.state.value.sessionsTruncated)
            } finally { release.countDown() }
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `complete background index omission retires old selected history only after last page`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.leaveSession()
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.sessionPage = { request ->
                val id = if (request.requestUrl!!.queryParameter("cursor") == null) "other" else "last"
                val item = session().replace("\"id\":\"chat\"", "\"id\":\"$id\"")
                if (id == "other") json("""{"items":[$item],"nextCursor":"next"}""")
                else { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); json("""{"items":[$item],"nextCursor":null}""") }
            }
            repo.refresh()
            try {
                withTimeout(5000) { entered.await() }
                assertEquals("chat", store.value.selectedSessionId)
                assertEquals("chat", repo.state.value.snapshot?.session?.id)
            } finally { release.countDown() }
            withTimeout(5000) { repo.state.first { !it.sessionsTruncated } }
            assertNull(store.value.selectedSessionId)
            assertNull(repo.state.value.snapshot)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `network wake reconciles uncertain send by reads without replaying the one POST`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.sendMessage("Synthetic uncertain intent")
            assertEquals(1, fixture.mutations.size)
            assertNotNull(store.value.pending)
            fixture.failIndex = true; repo.refresh()
            fixture.failIndex = false
            repo.networkRestored()
            withTimeout(5000) { repo.state.first { it.connection == ConnectionState.ONLINE && !it.busy } }
            // ONLINE precedes receipt reconciliation; wait for the read boundary, not UI state alone.
            withTimeout(5000) { while (fixture.requests.none { it.path?.startsWith("/v1/commands/") == true }) kotlinx.coroutines.delay(1) }
            assertEquals(1, fixture.mutations.size)
            assertNotNull(store.value.pending)
            assertTrue(fixture.requests.any { it.path?.startsWith("/v1/commands/") == true })
        }
    }
    @Test fun `manual refresh stays active after capabilities until slow history finishes`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); json(snapshot()) }
            val refresh = async(Dispatchers.Default) { repo.refresh() }
            try {
                withTimeout(5000) { entered.await() }
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                assertTrue(repo.state.value.manualRefreshing)
            } finally { release.countDown() }
            refresh.await(); assertFalse(repo.state.value.manualRefreshing)
            fixture.chatSnapshot = { json("{}") }
            repo.refresh(); assertFalse(repo.state.value.manualRefreshing)
        }
    }
    @Test fun `notification target during manual metadata refresh skips old history and preserves refresh lifetime`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.metadataGate = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
            val oldGets = fixture.requests.count { it.path == "/v1/sessions/chat" }
            val refresh = scope.async { repo.refresh() }
            var navigation: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                withTimeout(5000) { entered.await() }
                assertTrue(repo.state.value.manualRefreshing)
                navigation = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.selectSession("other") }
                assertTrue(repo.state.value.manualRefreshing)
            } finally { release.countDown() }
            refresh.await(); navigation!!.await()
            assertFalse(repo.state.value.manualRefreshing)
            assertEquals(oldGets, fixture.requests.count { it.path == "/v1/sessions/chat" })
            assertEquals(1, fixture.requests.count { it.path == "/v1/sessions/other" })
            assertEquals("other", repo.state.value.snapshot?.session?.id)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `cold refresh preserves indicator while publishing restored state`() = runBlocking {
        withRepository { _, store, _, scope ->
            val cold = NetworkMobileRepository(store, scope, debug = true)
            val restoredFlags = mutableListOf<Boolean>()
            val collector = scope.launch(Dispatchers.Unconfined) { cold.state.collect { if (it.draftsRestored) restoredFlags += it.manualRefreshing } }
            try {
                cold.refresh()
                assertTrue(restoredFlags.size >= 2)
                assertTrue(restoredFlags.dropLast(1).all { it })
                assertFalse(restoredFlags.last())
            } finally { collector.cancel(); cold.close() }
        }
    }
    @Test fun `cancelled manual refresh always clears its indicator`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); json(snapshot()) }
            val refresh = async(Dispatchers.Default) { repo.refresh() }
            try { withTimeout(5000) { entered.await() }; assertTrue(repo.state.value.manualRefreshing); refresh.cancel(); refresh.join() }
            finally { release.countDown() }
            assertFalse(repo.state.value.manualRefreshing)
        }
    }
    @Test fun `unavailable history refresh keeps authorized host online and disables stale host actions`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.chatSnapshot = { json("""{"error":{"code":"unavailable","message":"Synthetic","retryable":true}}""", 503) }
            repo.refresh()
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertEquals("chat_unavailable", repo.state.value.error)
            assertTrue(repo.state.value.snapshotStale)
            assertFalse(repo.state.value.busy)
            assertFalse(dev.dshmobile.app.ui.components.canCancel(repo.state.value))
            assertFalse(dev.dshmobile.app.model.MobileReducer.canSend(repo.state.value, "Synthetic draft"))
            repo.leaveSession()
            assertNull("Scoped history error must not follow Home", repo.state.value.error)
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertTrue(fixture.mutations.isEmpty())
        }
    }

    @Test fun `failed target remains chat scoped and explicit retry reads that target not the old selection`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh(); repo.leaveSession()
            val before = fixture.requests.count { it.path == "/v1/sessions/chat" }
            fixture.otherSnapshot = { json("""{"error":{"code":"payload_too_large","message":"Synthetic","retryable":false}}""", 413) }
            repo.selectSession("other")
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertEquals("chat_unavailable", repo.state.value.error)
            assertEquals("chat", store.value.selectedSessionId)
            assertTrue(repo.state.value.snapshotStale)
            fixture.otherSnapshot = null
            repo.selectSession("other")
            assertEquals("other", repo.state.value.snapshot?.session?.id)
            assertEquals("other", store.value.selectedSessionId)
            assertFalse(repo.state.value.snapshotStale)
            assertNull(repo.state.value.error)
            assertEquals(before, fixture.requests.count { it.path == "/v1/sessions/chat" })
            assertEquals(2, fixture.requests.count { it.path == "/v1/sessions/other" })
            assertTrue(fixture.mutations.isEmpty())
        }
    }

    @Test fun `SSE projection failure marks history stale without making paired host offline`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            fixture.events = { json("""{"error":{"code":"unavailable","message":"Synthetic","retryable":true}}""", 503) }
            repo.refresh()
            val failed = withTimeout(5_000) { repo.state.first { it.error == "chat_unavailable" } }
            assertEquals(ConnectionState.ONLINE, failed.connection)
            assertTrue(failed.snapshotStale)
            assertFalse(failed.busy)
            assertFalse(dev.dshmobile.app.model.MobileReducer.canSend(failed, "Synthetic draft"))
            repo.leaveSession()
            assertNull(repo.state.value.error)
            assertTrue(fixture.mutations.isEmpty())
        }
    }

    @Test fun `retryable SSE projection failure reopens automatically`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            var opens = 0
            fixture.events = {
                opens++
                if (opens == 1) json("""{"error":{"code":"unavailable","message":"Synthetic","retryable":true}}""", 503)
                else MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("event: snapshot\ndata: ${snapshot(cursor = 9)}\n\n")
            }
            repo.refresh()
            withTimeout(5_000) { repo.state.first { it.error == "chat_unavailable" } }
            val recovered = withTimeout(5_000) {
                repo.state.first { it.error == null && it.snapshot?.cursor == 9L && opens >= 2 }
            }
            assertFalse(recovered.snapshotStale)
            assertEquals(ConnectionState.ONLINE, recovered.connection)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `host is not online before authoritative metadata completes`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.metadataGate = { requested.complete(Unit); release.await(5, TimeUnit.SECONDS) }
            val refresh = scope.async { repo.refresh() }
            try {
                withTimeout(5000) { requested.await() }
                assertEquals(ConnectionState.SYNCING, repo.state.value.connection)
                assertTrue(repo.state.value.busy)
                assertFalse(dev.dshmobile.app.model.MobileReducer.canSend(repo.state.value, "Synthetic"))
                assertFalse(dev.dshmobile.app.ui.components.canCancel(repo.state.value))
            } finally { release.countDown(); refresh.await() }
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertFalse(repo.state.value.busy)
        }
    }
    @Test fun `session index starts before capability negotiation completes and unsupported results are discarded`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.capabilities = fixture.capabilities.replace("\"sessions\":true", "\"sessions\":false")
            fixture.metadataGate = { release.await(5, TimeUnit.SECONDS) }
            val before = fixture.requests.count { it.requestUrl?.encodedPath == "/v1/sessions" }
            val refresh = scope.async { repo.refresh() }
            try {
                withTimeout(1500) {
                    while (fixture.requests.count { it.requestUrl?.encodedPath == "/v1/sessions" } == before) kotlinx.coroutines.delay(10)
                }
            } finally { release.countDown(); refresh.await() }
            assertTrue(repo.state.value.sessions.isEmpty())
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
        }
    }
    @Test fun `list refresh does not serialize independent metadata round trips`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.metadataDelayMs = 500
            val started = System.nanoTime()
            repo.refresh()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("Synthetic refresh: ${elapsedMs}ms, three independent 500ms metadata bodies")
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertTrue("Independent metadata should overlap, observed ${elapsedMs}ms", elapsedMs < 1_350)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `reopening chat displays last history before a delayed authoritative replacement`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            fixture.snapshotText = snapshot("""[{"id":"old","role":"assistant","text":"Last known answer","createdAt":10}]""")
            repo.refresh()
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            repo.selectSession("other")
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = {
                requested.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                json(snapshot("""[{"id":"new","role":"assistant","text":"Replaced answer","createdAt":20}]""", cursor = 2))
            }
            val navigation = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.selectSession("chat") }
            try {
                requested.await()
                assertEquals("chat", repo.state.value.snapshot?.session?.id)
                assertEquals(listOf("old"), repo.state.value.snapshot?.messages?.map { it.id })
                assertTrue("Cached history must not authorize commands", repo.state.value.busy)
                release.countDown()
                navigation.await()
                assertEquals(listOf("new"), repo.state.value.snapshot?.messages?.map { it.id })
                assertFalse(repo.state.value.busy)
                assertTrue(fixture.mutations.isEmpty())
            } finally { release.countDown(); navigation.cancel() }
        }
    }
    @Test fun `leaving a loading chat cancels observation without waiting or touching host work`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.otherSnapshot = {
                requested.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                json(snapshot().replace("\"id\":\"chat\"", "\"id\":\"other\""))
            }
            val navigation = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.selectSession("other") }
            try {
                requested.await()
                withTimeout(750) { repo.leaveSession() }
                withTimeout(750) { navigation.await() }
                assertFalse(repo.state.value.busy)
                assertEquals("chat", repo.state.value.snapshot?.session?.id)
                assertTrue(fixture.mutations.isEmpty())
                release.countDown()
                fixture.otherSnapshot = null
                repo.selectSession("other")
                assertEquals("other", repo.state.value.snapshot?.session?.id)
            } finally { release.countDown(); navigation.cancel() }
        }
    }
    @Test fun `cached reopen cannot count as fresh receipt evidence and authoritative empty erases old history`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.snapshotText = snapshot(running = true)
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Synthetic queued inbox")
            fixture.snapshotText = snapshot("""[{"id":"old","role":"assistant","text":"Old cut","createdAt":10}]""")
            repo.refresh() // Only one real idle observation so far.
            repo.selectSession("other")
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = {
                requested.complete(Unit); release.await(5, TimeUnit.SECONDS)
                json(snapshot(cursor = 0))
            }
            val navigation = scope.async { repo.selectSession("chat") }
            try {
                requested.await()
                assertEquals(listOf("old"), repo.state.value.snapshot?.messages?.map { it.id })
                assertTrue(repo.state.value.snapshotStale)
                assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
                assertEquals(1, store.value.acceptedPrompts.single().idleSnapshots)
                release.countDown(); navigation.await()
                assertTrue(repo.state.value.snapshot!!.messages.isEmpty())
                assertEquals(0L, repo.state.value.snapshot?.cursor)
                assertFalse(repo.state.value.snapshotStale)
                assertEquals("unconfirmed", repo.state.value.acceptedPrompts.single().status)
                assertEquals(1, fixture.mutations.size) // Only the explicit synthetic send; no cached replay.
            } finally { release.countDown(); navigation.cancel() }
        }
    }
    @Test fun `identical idle cuts are not separate expiry evidence`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Synthetic queued inbox")
            fixture.snapshotText = snapshot(cursor = 7)
            repo.refresh()
            assertEquals(1, store.value.acceptedPrompts.single().idleSnapshots)
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)

            repo.refresh() // Same durable cut delivered again: no new evidence.
            assertEquals(1, store.value.acceptedPrompts.single().idleSnapshots)
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            assertNull(repo.state.value.error)
        }
    }
    @Test fun `cached display is replaced by the first SSE cut even when its cursor goes backwards`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot("""[{"id":"discarded","role":"assistant","text":"Previous cut","createdAt":10}]""", cursor = 90)
            repo.refresh()
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh(); repo.selectSession("other")
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            fixture.otherSnapshot = { json(snapshot().replace("\"id\":\"chat\"", "\"id\":\"other\"")) }
            val replacement = snapshot("""[{"id":"canonical","role":"assistant","text":"Authoritative new cut","createdAt":20}]""", cursor = 2)
            val initial = "event: snapshot\ndata: $replacement\n\n"
            fixture.events = { MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(initial + ": heartbeat\n\n").throttleBody(initial.toByteArray().size.toLong(), 1, TimeUnit.SECONDS) }
            // Refresh capabilities while observing no chat, then open the cached target.
            repo.leaveSession(); repo.refresh(); repo.selectSession("chat")
            withTimeout(5_000) { repo.state.first { it.snapshot?.cursor == 2L } }
            assertEquals(listOf("canonical"), repo.state.value.snapshot!!.messages.map { it.id })
            assertFalse(repo.state.value.snapshotStale)
            repo.leaveSession()
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `back during refresh cancels only history projection and releases list admission`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = {
                requested.complete(Unit); release.await(5, TimeUnit.SECONDS)
                json(snapshot(cursor = 90))
            }
            val refresh = scope.async { repo.refresh() }
            try {
                requested.await()
                withTimeout(750) { repo.leaveSession(); refresh.await() }
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                assertFalse(repo.state.value.busy)
                assertTrue(repo.state.value.snapshotStale)
                assertEquals(1L, repo.state.value.snapshot?.cursor)
                assertEquals(listOf("chat"), repo.state.value.sessions.map { it.id })
                assertTrue(fixture.mutations.isEmpty())
            } finally { release.countDown(); refresh.cancel() }
        }
    }
    @Test fun `back during offline chat metadata refresh cannot strand Home in syncing`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            repo.setForeground(false)
            fixture.failIndex = true
            repo.setForeground(true)
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            fixture.failIndex = false
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.metadataGate = { requested.complete(Unit); release.await(5, TimeUnit.SECONDS) }
            val navigation = scope.async { repo.selectSession("chat") }
            try {
                withTimeout(5_000) { requested.await() }
                assertEquals(ConnectionState.SYNCING, repo.state.value.connection)
                withTimeout(750) { repo.leaveSession(); navigation.await() }
                assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
                assertFalse(repo.state.value.busy)
                release.countDown(); fixture.metadataGate = null
                repo.refresh()
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
                assertFalse(repo.state.value.busy)
                assertTrue(dev.dshmobile.app.ui.components.canCreate(repo.state.value))
                repo.selectSession("chat")
                assertFalse(repo.state.value.snapshotStale)
                assertTrue(fixture.mutations.isEmpty())
            } finally { release.countDown(); navigation.cancel() }
        }
    }
    @Test fun `cold home restore publishes list without waiting for last selected chat history`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            repo.close()
            fixture.chatSnapshot = { error("Cold home must not read last selected history") }
            val restoredHome = NetworkMobileRepository(store, scope, true, observeRestoredSession = false)
            try {
                restoredHome.setForeground(true)
                assertEquals(ConnectionState.ONLINE, restoredHome.state.value.connection)
                assertEquals(listOf("chat"), restoredHome.state.value.sessions.map { it.id })
                assertNull(restoredHome.state.value.snapshot)
                assertTrue(fixture.mutations.isEmpty())
            } finally { restoredHome.close() }
        }
    }
    @Test fun `home refresh avoids an unobserved history read and does not reconcile stale receipts`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.leaveSession()
            fixture.chatSnapshot = { error("Home must not project chat history") }
            repo.refresh()
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertTrue(repo.state.value.snapshotStale)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `expired cached history is not shown and revocation hides last known text`() = runBlocking {
        var clock = 1_000L
        withRepository(now = { clock }) { repo, _, fixture, scope ->
            fixture.snapshotText = snapshot("""[{"id":"private-old","role":"assistant","text":"Synthetic old history","createdAt":10}]""")
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh(); repo.selectSession("other")
            clock += 24 * 60 * 60 * 1_000L + 1
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = {
                requested.complete(Unit); release.await(5, TimeUnit.SECONDS)
                json("""{"error":{"code":"revoked","message":"Synthetic","retryable":false}}""", 401)
            }
            val navigation = scope.async { repo.selectSession("chat") }
            try {
                requested.await()
                assertNotEquals("chat", repo.state.value.snapshot?.session?.id)
                release.countDown(); navigation.await()
                assertEquals(ConnectionState.REVOKED, repo.state.value.connection)
                assertNull(repo.state.value.snapshot)
                assertTrue(fixture.mutations.isEmpty())
            } finally { release.countDown(); navigation.cancel() }
        }
    }
    @Test fun `notification for last chat after Home resumes SSE without duplicate GET or commands`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.leaveSession()
            fixture.capabilities = fixture.capabilities.replace("\"liveSnapshots\":false", "\"liveSnapshots\":true")
            repo.refresh()
            assertTrue(repo.state.value.snapshotStale)
            assertFalse(repo.state.value.observingSession)
            assertTrue(dev.dshmobile.app.ui.chatNeedsSelection("chat", repo.state.value, false))
            val getCount = fixture.requests.count { it.path == "/v1/sessions/chat" }
            val eventsCount = fixture.requests.count { it.path == "/v1/sessions/chat/events" }
            val replacement = snapshot("""[{"id":"notification-fresh","role":"assistant","text":"New cut","createdAt":20}]""", cursor = 2)
            val opening = "event: snapshot\ndata: $replacement\n\n"
            // A complete finite body immediately reports EOF and races selection
            // assertions with reconnect/offline handling. Advertise one more byte:
            // the real socket stays open until cancellation, with no sleeping writer.
            fixture.events = { MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(opening).setHeader("Content-Length", opening.toByteArray().size + 1) }
            repo.selectSession("chat")
            withTimeout(5_000) { repo.state.first { it.snapshot?.cursor == 2L } }
            assertEquals(getCount, fixture.requests.count { it.path == "/v1/sessions/chat" })
            assertTrue(fixture.requests.count { it.path == "/v1/sessions/chat/events" } > eventsCount)
            assertTrue(repo.state.value.observingSession)
            assertFalse(repo.state.value.snapshotStale)
            assertTrue(dev.dshmobile.app.ui.notificationTargetSelected("chat", repo.state.value))
            assertTrue(dev.dshmobile.app.model.MobileReducer.canSend(repo.state.value, "Synthetic draft"))
            repo.leaveSession()
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `notification navigation refreshes newly authorized chat and cold state without mutations`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.selectSession("other")
            assertNull(repo.state.value.error)
            assertEquals("other", repo.state.value.snapshot?.session?.id)
            assertEquals("other", store.value.selectedSessionId)
            assertTrue(fixture.requests.any { it.requestUrl?.encodedPath == "/v1/sessions" }); assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `navigation metadata refresh reads only the target history once`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.failIndex = true; repo.refresh()
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            fixture.failIndex = false
            val beforeSame = fixture.requests.count { it.path == "/v1/sessions/chat" }
            repo.selectSession("chat")
            assertEquals("Offline entry must not GET the last chat in refresh then GET it again", beforeSame + 1,
                fixture.requests.count { it.path == "/v1/sessions/chat" })
            val beforeOther = fixture.requests.count { it.path == "/v1/sessions/chat" }
            repo.selectSession("other") // Target is not in the previous bounded catalogue.
            assertEquals("A catalogue miss must not project the previous selected chat", beforeOther,
                fixture.requests.count { it.path == "/v1/sessions/chat" })
            assertEquals(1, fixture.requests.count { it.path == "/v1/sessions/other" })
            assertEquals("other", repo.state.value.snapshot?.session?.id)
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `reconnect retries failed notification target without refreshing previous chat`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.updateDraft("A draft survives")
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            fixture.otherSnapshot = { MockResponse().setResponseCode(503) }
            repo.leaveSession(); repo.selectSession("other")
            assertNotNull(repo.state.value.error)
            assertEquals("chat", store.value.selectedSessionId)
            val previousGets = fixture.requests.count { it.path == "/v1/sessions/chat" }
            fixture.otherSnapshot = { json(snapshot("""[{"id":"fresh-B","role":"assistant","text":"B recovered","createdAt":20}]""", cursor = 2).replace("\"id\":\"chat\"", "\"id\":\"other\"")) }
            // Same refresh boundary used by automatic reconnect, not a second selectSession call.
            repo.refresh()
            assertEquals("A must never refresh under B's route", previousGets, fixture.requests.count { it.path == "/v1/sessions/chat" })
            assertEquals("other", store.value.selectedSessionId)
            assertEquals("fresh-B", repo.state.value.snapshot?.messages?.single()?.id)
            assertTrue(dev.dshmobile.app.ui.notificationTargetSelected("other", repo.state.value))
            assertTrue(dev.dshmobile.app.model.MobileReducer.canSend(repo.state.value, "B draft"))
            assertEquals("A draft survives", store.value.drafts["chat"])
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `notification navigation retries cold offline failure without a command or losing drafts`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.updateDraft("draft survives navigation")
            fixture.failIndex = true; repo.refresh(); assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            repo.selectSession("other"); assertNotNull(repo.state.value.error); assertEquals("chat", store.value.selectedSessionId)
            fixture.failIndex = false
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.selectSession("other"); assertEquals("other", store.value.selectedSessionId)
            assertEquals("draft survives navigation", store.value.drafts["chat"]); assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `notification target beyond bounded index loads only an authorized snapshot`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            repo.selectSession("other"); assertEquals("other", store.value.selectedSessionId)
            assertTrue(repo.state.value.sessions.any { it.id == "other" }); assertTrue(fixture.mutations.isEmpty())
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
    @Test fun `accepted prompt during running releases admission and clears only its submitted draft`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.updateDraft("First queued prompt")
            repo.sendMessage("First queued prompt")
            assertNull("Accepted inbox prompt must not block admission", repo.state.value.pending)
            assertEquals("", repo.state.value.draft)
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            assertEquals("First queued prompt", repo.state.value.acceptedPrompts.single().text)
            repo.updateDraft("Second queued prompt")
            repo.sendMessage("Second queued prompt")
            assertEquals(2, fixture.mutations.size)
            assertEquals(2, repo.state.value.acceptedPrompts.size)
            val firstId = requestId(fixture.mutations.first())
            fixture.snapshotText = snapshot("""[{"id":"canonical-first","role":"user","text":"First queued prompt","createdAt":30,"requestId":"$firstId"}]""", running = true)
            repo.refresh()
            assertEquals(listOf("Second queued prompt"), repo.state.value.acceptedPrompts.map { it.text })
            assertEquals(listOf("canonical-first"), repo.state.value.snapshot!!.messages.map { it.id })
            repo.refresh()
            assertEquals(1, repo.state.value.acceptedPrompts.size)
            assertEquals(2, fixture.mutations.size)
        }
    }
    @Test fun `three admitted prompts persist across recreation and a fourth waits for canonical history`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repeat(3) { repo.sendMessage("Queued synthetic $it") }
            assertEquals(3, store.value.acceptedPrompts.size)
            assertNull(store.value.pending)
            repo.close()
            val recreated = NetworkMobileRepository(store, scope, true)
            try {
                recreated.restore()
                assertEquals(listOf("queued", "queued", "queued"), recreated.state.value.acceptedPrompts.map { it.status })
                assertNull(recreated.state.value.error)
                recreated.setForeground(true)
                recreated.sendMessage("Fourth must wait")
                assertEquals("command_unresolved", recreated.state.value.error)
                assertEquals(3, fixture.mutations.size)
                val id = requestId(fixture.mutations.first())
                fixture.snapshotText = snapshot("""[{"id":"first","role":"user","text":"Queued synthetic 0","createdAt":30,"requestId":"$id"}]""", running = true)
                recreated.refresh()
                recreated.sendMessage("Fourth now admitted")
                assertEquals(4, fixture.mutations.size)
                assertEquals(3, recreated.state.value.acceptedPrompts.size)
            } finally { recreated.close() }
        }
    }
    @Test fun `stop with three queued prompts expires after two idle snapshots across restart`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repeat(3) { repo.sendMessage("Followup $it") }
            fixture.message = { request ->
                fixture.snapshotText = snapshot(running = false)
                json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""")
            }
            repo.cancelRun() // First authoritative idle observation.
            assertEquals(listOf("queued", "queued", "queued"), repo.state.value.acceptedPrompts.map { it.status })
            repo.close()
            val restarted = NetworkMobileRepository(store, scope, true)
            try {
                fixture.snapshotText = snapshot(running = false, cursor = 2)
                restarted.setForeground(true) // Second distinct idle cut.
                assertEquals(listOf("unconfirmed", "unconfirmed", "unconfirmed"), restarted.state.value.acceptedPrompts.map { it.status })
                restarted.sendMessage("Wake retained inbox")
                assertEquals(5, fixture.mutations.size)
            } finally { restarted.close() }
        }
    }
    @Test fun `a full queued chat does not block another chat`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repeat(3) { repo.sendMessage("Followup $it") }
            repo.selectSession("other")
            repo.sendMessage("Other chat must not be blocked")
            assertTrue(fixture.requests.any { it.path == "/v1/sessions/other/messages" && it.method == "POST" })
        }
    }
    @Test fun `dismiss queued and unconfirmed bubbles is local and persists without touching draft`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Dismiss queued")
            val queuedId = repo.state.value.acceptedPrompts.single().requestId
            repo.updateDraft("Keep draft")
            val before = fixture.requests.size
            repo.state.value.localPromptActions!!.dismiss(queuedId)
            assertEquals(before, fixture.requests.size)
            assertEquals("Keep draft", repo.state.value.draft)
            assertTrue(store.value.acceptedPrompts.isEmpty())
            repo.sendMessage("Dismiss expired")
            fixture.snapshotText = snapshot(running = false)
            repo.refresh()
            fixture.snapshotText = snapshot(running = false, cursor = 2)
            repo.refresh()
            val local = repo.state.value.acceptedPrompts.single()
            assertEquals("unconfirmed", local.status)
            val afterExpiry = fixture.requests.size
            repo.state.value.localPromptActions!!.dismiss(local.requestId)
            assertEquals(afterExpiry, fixture.requests.size)
            repo.close()
            val restarted = NetworkMobileRepository(store, scope, true)
            try { restarted.restore(); assertTrue(restarted.state.value.acceptedPrompts.isEmpty()) }
            finally { restarted.close() }
            assertEquals(2, fixture.mutations.size)
        }
    }
    @Test fun `explicit resend creates new ID preserves original and survives restart without rePOST`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Explicit repeat")
            val originalId = repo.state.value.acceptedPrompts.single().requestId
            fixture.snapshotText = snapshot(running = false)
            repo.refresh()
            fixture.snapshotText = snapshot(running = false, cursor = 2)
            repo.refresh()
            assertEquals("unconfirmed", repo.state.value.acceptedPrompts.single().status)
            repo.state.value.localPromptActions!!.resend(originalId)
            assertEquals(2, fixture.mutations.size)
            val newId = requestId(fixture.mutations.last())
            assertNotEquals(originalId, newId)
            assertEquals(listOf(originalId, newId), store.value.acceptedPrompts.map { it.requestId })
            assertEquals(listOf("Explicit repeat", "Explicit repeat"), store.value.acceptedPrompts.map { it.text })
            repo.close()
            val restarted = NetworkMobileRepository(store, scope, true)
            try {
                restarted.restore()
                assertEquals("unconfirmed", restarted.state.value.acceptedPrompts.first().status)
                restarted.setForeground(true)
                assertEquals(2, fixture.mutations.size)
                fixture.snapshotText = snapshot("""[{"id":"old-canonical","role":"user","text":"Explicit repeat","createdAt":30,"requestId":"$originalId"}]""")
                restarted.refresh()
                assertEquals(listOf(newId), restarted.state.value.acceptedPrompts.map { it.requestId })
            } finally { restarted.close() }
        }
    }
    @Test fun `lost explicit resend response is uncertain and never automatically POSTed again`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Explicit repeated lost response")
            val original = repo.state.value.acceptedPrompts.single().requestId
            fixture.snapshotText = snapshot(running = false)
            repo.refresh()
            fixture.snapshotText = snapshot(running = false, cursor = 2)
            repo.refresh()
            fixture.message = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            repo.state.value.localPromptActions!!.resend(original)
            assertEquals("uncertain", repo.state.value.pending!!.status)
            assertNotEquals(original, repo.state.value.pending!!.requestId)
            repo.refresh(); repo.resolvePending()
            assertEquals(2, fixture.mutations.size)
        }
    }
    @Test fun `running snapshot resets idle expiry and later canonical message reconciles unconfirmed entry`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Retained inbox")
            val id = repo.state.value.acceptedPrompts.single().requestId
            fixture.snapshotText = snapshot(running = false); repo.refresh()
            fixture.snapshotText = snapshot(running = true, cursor = 2); repo.refresh()
            fixture.snapshotText = snapshot(running = false, cursor = 3); repo.refresh()
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            fixture.snapshotText = snapshot(running = false, cursor = 4); repo.refresh()
            assertEquals("unconfirmed", repo.state.value.acceptedPrompts.single().status)
            fixture.snapshotText = snapshot("""[{"id":"canonical","role":"user","text":"Retained inbox","createdAt":30,"requestId":"$id"}]""")
            repo.refresh()
            assertTrue(repo.state.value.acceptedPrompts.isEmpty())
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `accepted running entries expire at ten minutes only after a fresh snapshot`() = runBlocking {
        var clock = 1_000L
        withRepository(now = { clock }) { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Long retained inbox")
            clock += 599_999L
            repo.refresh()
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            clock++
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            repo.refresh()
            assertEquals("unconfirmed", repo.state.value.acceptedPrompts.single().status)
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `lost second running response stays uncertain beside queued prompt and resolves only by GET`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request -> json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""") }
            repo.sendMessage("Accepted first")
            fixture.message = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            repo.sendMessage("Lost second")
            val id = repo.state.value.pending!!.requestId
            assertEquals("uncertain", repo.state.value.pending!!.status)
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            repo.refresh()
            repo.sendMessage("Must not send")
            assertEquals(2, fixture.mutations.size)
            fixture.receipt = { requested -> json("""{"requestId":"$requested","status":"accepted","updatedAt":30}""") }
            repo.resolvePending()
            assertNull(repo.state.value.pending)
            assertEquals(listOf("queued", "queued"), repo.state.value.acceptedPrompts.map { it.status })
            assertEquals(id, repo.state.value.acceptedPrompts.last().requestId)
            assertEquals(2, fixture.mutations.size)
        }
    }
    @Test fun `accepted inbox prompt remains queued when a later snapshot GET fails`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            fixture.message = { request ->
                fixture.snapshotText = "{}"
                json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""")
            }
            repo.sendMessage("Accepted before failed GET")
            assertNull(store.value.pending)
            assertEquals("queued", store.value.acceptedPrompts.single().status)
            fixture.snapshotText = snapshot(running = true)
            repo.refresh()
            assertEquals("queued", repo.state.value.acceptedPrompts.single().status)
            assertNull(repo.state.value.error)
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
    @Test fun `not sent notice survives encrypted JSON roundtrip and damaged optional notice is isolated`() {
        val original = StoredState(selectedSessionId = "chat", drafts = mapOf("chat" to "Synthetic retained"), notSentSessions = setOf("chat"))
        val restored = decodeStoredState(mobileJson.encodeToString(StoredState.serializer(), original))
        assertEquals(setOf("chat"), restored.notSentSessions)
        val damaged = decodeStoredState("""{"selectedSessionId":"chat","drafts":{"chat":"Synthetic retained"},"notSentSessions":{"bad":true}}""")
        assertEquals("Synthetic retained", damaged.drafts["chat"])
        assertTrue(damaged.notSentSessions.isEmpty())
    }
    @Test fun `preheader connection recovery retries bounded while backgrounded and admits exactly one POST`() = runBlocking {
        // Keep the serving port alive. A test-process ProxySelector routes only
        // the first mutation through a stopped loopback proxy: a real refused TCP
        // connection before HTTP headers, with no same-port server restart.
        val previousSelector = java.net.ProxySelector.getDefault()
        val refusedAddress = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
            java.net.InetSocketAddress("127.0.0.1", it.localPort)
        }
        val refuse = java.util.concurrent.atomic.AtomicBoolean(false)
        // Prove this route is refused, not an HTTP failure or a connect timeout.
        try { java.net.Socket().use { it.connect(refusedAddress, 1_000) }; fail("Proxy must refuse TCP") }
        catch (_: java.net.ConnectException) { }
        val refusedRoutes = java.util.concurrent.atomic.AtomicInteger()
        val retries = mutableListOf<Int>()
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI): List<java.net.Proxy> =
                if (refuse.get()) {
                    refusedRoutes.incrementAndGet()
                    listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, refusedAddress))
                } else listOf(java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: java.net.URI, address: java.net.SocketAddress, failure: java.io.IOException) {
                // OkHttp retryOnConnectionFailure(false) need not invoke this callback.
                assertEquals(refusedAddress, address)
                assertTrue("Expected actual TCP refusal, got $failure", failure is java.net.ConnectException)
            }
        })
        try {
            withRepository(preHeaderRetryDelay = { attempt ->
                retries += attempt
                assertEquals(0, attempt)
                assertTrue("The mutation must select the refused TCP route", refusedRoutes.get() > 0)
                refuse.set(false)
                0L
            }) { repo, store, fixture, scope ->
                fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
                repo.updateDraft("Synthetic recovery")
                store.gate = CompletableDeferred()
                val send = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.sendMessage("Synthetic recovery") }
                refuse.set(true)
                val stop = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false) }
                store.gate!!.complete(Unit); send.await(); stop.await()
                assertEquals(listOf(0), retries)
                assertTrue("The mutation must select the refused TCP route", refusedRoutes.get() > 0)
                assertNull(store.value.pending)
                assertFalse(repo.state.value.sendNotSent)
                assertEquals("accepted", store.value.acceptedPrompts.single().status)
                assertEquals(1, fixture.mutations.size)
            }
        } finally { java.net.ProxySelector.setDefault(previousSelector) }
    }
    @Test fun `connection refused before headers is not sent and retains draft without an uncertain record`() = runBlocking {
        val attempts = mutableListOf<Int>()
        withRepository(preHeaderRetryDelay = { attempts += it; 0L }) { repo, store, fixture, scope ->
            repo.updateDraft("Synthetic not sent")
            store.gate = CompletableDeferred()
            val send = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.sendMessage("Synthetic not sent") }
            fixture.stopServer()
            val stop = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false) }
            store.gate!!.complete(Unit)
            send.await(); stop.await()
            assertTrue(fixture.mutations.isEmpty())
            assertNull(store.value.pending)
            assertEquals("offline_no_send", repo.state.value.error)
            assertEquals(listOf(0, 1), attempts)
            assertTrue(repo.state.value.sendNotSent)
            assertEquals("Synthetic not sent", repo.state.value.draft)
        }
    }
    @Test fun `send refused by background admission retains explicit not sent across reconnect and restart`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            repo.updateDraft("Synthetic not admitted")
            repo.setForeground(false)
            repo.sendMessage("Synthetic not admitted")
            assertTrue(repo.state.value.sendNotSent)
            assertEquals("Synthetic not admitted", repo.state.value.draft)
            repo.setForeground(true)
            assertTrue(repo.state.value.sendNotSent)
            repo.close()
            val restored = NetworkMobileRepository(store, scope, true)
            try { restored.restore(); assertTrue(restored.state.value.sendNotSent) } finally { restored.close() }
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `host recovery hold is a definite pre-admission refusal that keeps the draft and never becomes uncertain`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.message = { json("""{"error":{"code":"recovery_hold","message":"Synthetic","retryable":false}}""", 503) }
            repo.updateDraft("Synthetic during recovery")
            repo.sendMessage("Synthetic during recovery")
            assertNull(store.value.pending)
            assertEquals("recovery_hold", repo.state.value.error)
            assertEquals("Synthetic during recovery", repo.state.value.draft)
            assertTrue(repo.state.value.sendNotSent)
            assertEquals(1, fixture.mutations.size)
            assertTrue("a held command must not be looked up or replayed", fixture.requests.none { it.path?.startsWith("/v1/commands/") == true })
        }
    }
    @Test fun `send accepted after Home while response is in flight is not cancelled`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.message = { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
            val send = scope.async { repo.sendMessage("Synthetic in flight") }
            withTimeout(2000) { entered.await() }
            val stop = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false) }
            release.countDown(); send.await(); stop.await()
            assertNull(store.value.pending)
            assertEquals("accepted", store.value.acceptedPrompts.single().status)
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `send admitted before Home drains one POST even when background arrives during persistence`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
            repo.updateDraft("Synthetic Home send")
            store.gate = CompletableDeferred()
            val send = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.sendMessage("Synthetic Home send") }
            val stop = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false) }
            store.gate!!.complete(Unit)
            send.await(); stop.await()
            assertEquals("Home must not cancel a durable send before POST", 1, fixture.mutations.size)
            assertNull(store.value.pending)
            assertEquals("accepted", store.value.acceptedPrompts.single().status)
            assertEquals("", repo.state.value.draft)
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
                releaseResponse.countDown() // Background no longer aborts the in-flight exchange.
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
                assertEquals("Initial stream-first + GET-fallback observer, then six scheduled retries", 8, fixture.requests.count { it.path == "/v1/sessions/chat/events" })
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
    @Test fun `forget falls back to a destructive reset when clear cannot use the key`() = runBlocking {
        withRepository { repo, store, _, _ ->
            store.failClear = true
            repo.forget()
            assertEquals(1, store.resetCalls)
            assertFalse(repo.state.value.paired)
            assertNull(store.value.host)
            assertNull(repo.state.value.error)
        }
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
    @Test fun `offline stale chat draft stays editable and durable without host dispatch`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.failIndex = true
            repo.refresh()
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
            assertTrue(repo.state.value.snapshotStale)
            assertTrue(dev.dshmobile.app.ui.components.canEditDraft(repo.state.value))
            repo.updateDraft("Offline local draft")
            assertEquals("Offline local draft", repo.state.value.draft)
            assertEquals("Offline local draft", store.value.drafts["chat"])
            assertFalse(dev.dshmobile.app.model.MobileReducer.canSend(repo.state.value, repo.state.value.draft))
            repo.close()
            val restored = NetworkMobileRepository(store, scope, true)
            try { restored.restore(); assertEquals("Offline local draft", restored.state.value.draft) }
            finally { restored.close() }
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `drafts stay isolated across navigation and repository restart`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            repo.refresh()
            repo.updateDraft("Черновик A")
            repo.selectSession("other")
            assertEquals("", repo.state.value.draft)
            repo.updateDraft("Draft B")
            repo.selectSession("chat")
            assertEquals("Черновик A", repo.state.value.draft)
            repo.close()
            val restarted = NetworkMobileRepository(store, scope, true)
            try {
                restarted.restore()
                assertEquals("Черновик A", restarted.state.value.draft)
                restarted.setForeground(true)
                restarted.selectSession("other")
                assertEquals("Draft B", restarted.state.value.draft)
                restarted.updateDraft("")
                restarted.selectSession("chat")
                assertEquals("Черновик A", restarted.state.value.draft)
                assertTrue(fixture.mutations.isEmpty())
            } finally { restarted.close() }
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
    @Test fun `disk index appears before network after process death and revoked read erases disk history`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.snapshotText = snapshot("""[{"id":"old","role":"assistant","text":"Synthetic cached","createdAt":10}]""")
            repo.refresh()
            val disk = object : HistoryStore {
                override val authority = HistoryAuthority()
                var value = HistoryRecord()
                override suspend fun read() = value
                override suspend fun write(value: HistoryRecord) { this.value = value }
                override suspend fun clear() { value = HistoryRecord() }
            }
            val caching = NetworkMobileRepository(store, scope, true, historyStore = disk)
            caching.setForeground(true); caching.selectSession("chat"); caching.setForeground(false); caching.close()
            val recreated = NetworkMobileRepository(store, scope, true, observeRestoredSession = false, historyStore = disk)
            try {
                recreated.restore()
                assertEquals(listOf("chat"), recreated.state.value.sessions.map { it.id })
                val requested = CompletableDeferred<Unit>()
                val release = java.util.concurrent.CountDownLatch(1)
                recreated.setForeground(true)
                fixture.chatSnapshot = { requested.complete(Unit); release.await(5, TimeUnit.SECONDS); json("""{"error":{"code":"revoked","message":"Synthetic","retryable":false}}""", 401) }
                val open = scope.async { recreated.selectSession("chat") }
                try {
                    requested.await()
                    assertEquals(listOf("old"), recreated.state.value.snapshot!!.messages.map { it.id })
                    assertTrue(recreated.state.value.snapshotStale)
                    assertTrue(dev.dshmobile.app.ui.components.canEditDraft(recreated.state.value, true))
                    assertFalse(dev.dshmobile.app.model.MobileReducer.canSend(recreated.state.value, "draft"))
                    release.countDown(); open.await()
                    assertNull(recreated.state.value.snapshot)
                    assertTrue(disk.value.entries.isEmpty())
                    assertTrue(recreated.state.value.sessions.isEmpty())
                    assertTrue(fixture.mutations.isEmpty())
                } finally { release.countDown(); open.cancel() }
            } finally { recreated.close() }
        }
    }
    @Test fun `service rejection clears live UI offline caches and prevents pending write resurrection`() = runBlocking {
        withRepository { _, store, fixture, scope ->
            val disk = MemoryHistoryStore()
            val live = NetworkMobileRepository(store, scope, true, historyStore = disk)
            try {
                live.setForeground(true); live.selectSession("chat"); live.setForeground(false)
                assertNotNull(live.state.value.snapshot)
                disk.authority.invalidate("revoked") { disk.clear() }
                assertNull(live.state.value.snapshot); assertTrue(live.state.value.sessions.isEmpty())
                assertEquals(ConnectionState.REVOKED, live.state.value.connection)
                live.leaveSession(); live.setForeground(false)
                assertTrue(disk.read().entries.isEmpty())
                // A later independently authorized refresh rebinds, not an old pending flush.
                live.setForeground(true); live.selectSession("chat"); live.setForeground(false)
                assertNotNull(live.state.value.snapshot)
                assertEquals(historyBinding(store.value.host!!), disk.read().binding)
                val recreated = PersistentHistory(disk, System::currentTimeMillis)
                recreated.restore(historyBinding(store.value.host!!)); assertNotNull(recreated.get("chat"))
                assertTrue(fixture.mutations.isEmpty())
            } finally { live.close() }
        }
    }
    @Test fun `home prefetch only issues bounded GETs and background cancels its held history requests`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            repo.close()
            fixture.index = """{"items":[${session()},${session().replace("\"id\":\"chat\"", "\"id\":\"other\"")}],"nextCursor":null}"""
            val requested = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.chatSnapshot = { requested.complete(Unit); release.await(5, TimeUnit.SECONDS); json(snapshot()) }
            val prefetching = NetworkMobileRepository(store, scope, true, observeRestoredSession = false, prefetchEnabled = true)
            try {
                prefetching.setForeground(true)
                withTimeout(5000) { requested.await() }
                withTimeout(1000) { prefetching.setForeground(false) }
                assertTrue(fixture.mutations.isEmpty())
                assertFalse(prefetching.state.value.observingSession)
                assertNull(prefetching.state.value.snapshot)
                assertEquals(ConnectionState.OFFLINE, prefetching.state.value.connection)
            } finally { release.countDown(); prefetching.close() }
        }
    }
    @Test fun `phone commands with absent capability or read only authority never POST`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            repo.rename("Synthetic title")
            repo.selectModel(dev.dshmobile.app.model.ModelSelection("fixture", "vision"))
            assertTrue(fixture.mutations.isEmpty())
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":true,\"rename\":true,\"models\":true,\"queue\":true")
            fixture.snapshotText = snapshot().replace("\"canExecute\":true", "\"canExecute\":false")
            repo.refresh(); repo.rename("Synthetic title"); repo.selectModel(dev.dshmobile.app.model.ModelSelection("fixture", "vision"))
            assertTrue(fixture.mutations.isEmpty())
        }
    }
    @Test fun `uncertain phone mutation survives restore and lookup without another POST`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":false,\"rename\":true")
            repo.refresh(); repo.rename("Synthetic title")
            assertEquals(1, fixture.mutations.size)
            val pending = store.value.pending!!
            repo.close()
            val restored = NetworkMobileRepository(store, scope, debug = true)
            try {
                restored.restore(); restored.setForeground(true); restored.resolvePending()
                assertEquals(pending.requestId, store.value.pending?.requestId)
                assertEquals("Synthetic title", store.value.pending?.title)
                assertEquals(1, fixture.mutations.size)
            } finally { restored.close() }
        }
    }
    @Test fun `expired question rejection releases pending and refreshes without replay`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"questions\":false", "\"questions\":true")
            fixture.snapshotText = snapshot().dropLast(1) + """, "pendingQuestions":[{"callId":"call","state":"open","questions":[{"id":"q","question":"Synthetic?","options":[{"label":"Yes"}],"multiSelect":false,"allowFreeText":false}]}]}"""
            fixture.message = { fixture.snapshotText = snapshot(); json("""{"error":{"code":"question_expired","message":"Synthetic","retryable":false}}""", 409) }
            repo.refresh(); repo.answer("call", dev.dshmobile.app.model.QuestionAnswer(listOf(dev.dshmobile.app.model.AnswerItem("q", listOf("Yes")))))
            assertNull(store.value.pending); assertTrue(repo.state.value.snapshot!!.pendingQuestions.isEmpty())
            repo.refresh(); assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `skip capability is opt in continued questions can answer and payload is exclusive`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"questions\":false", "\"questions\":true")
            fixture.snapshotText = snapshot().dropLast(1) + """, "pendingQuestions":[{"callId":"call","state":"continued","questions":[{"id":"q","question":"Synthetic?","options":[{"label":"Yes"}]}]}]}"""
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
            repo.refresh(); repo.answer("call", dev.dshmobile.app.model.QuestionAnswer(listOf(dev.dshmobile.app.model.AnswerItem("q", listOf("Yes")))))
            assertEquals(1, fixture.mutations.size)
            assertTrue(fixture.mutations.single().body.clone().readUtf8().contains("\"selected\":[\"Yes\"]"))
            fixture.mutations.clear()
            repo.skipQuestion("call"); assertTrue(fixture.mutations.isEmpty())
            fixture.capabilities = fixture.capabilities.replace("\"questions\":true", "\"questions\":true,\"questionSkip\":true")
            repo.refresh(); repo.skipQuestion("call")
            assertEquals(1, fixture.mutations.size)
            val body = fixture.mutations.single().body.clone().readUtf8()
            assertTrue(body.contains("\"skip\":true")); assertFalse(body.contains("\"answer\""))
        }
    }
    @Test fun `accepted local prompts survive partial host queue and reconcile matching request only`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":false,\"queue\":true")
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
            fixture.snapshotText = snapshot(running = true)
            repo.refresh(); repo.updateDraft("First synthetic"); repo.sendMessage("First synthetic")
            val first = store.value.acceptedPrompts.single().requestId
            repo.updateDraft("Second synthetic"); repo.sendMessage("Second synthetic")
            assertEquals(2, store.value.acceptedPrompts.size)
            fixture.snapshotText = snapshot().dropLast(1) + """, "pendingQueue":[{"id":"host-item","text":"First synthetic","delivery":"queue","requestId":"$first"}]}"""
            repo.refresh()
            assertEquals(listOf("Second synthetic"), store.value.acceptedPrompts.map { it.text })
            assertEquals(1, repo.state.value.snapshot!!.pendingQueue.size)
        }
    }
    @Test fun `conflicting request id never creates a replacement mutation`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":false,\"rename\":true")
            fixture.message = { json("""{"error":{"code":"request_conflict","message":"Synthetic","retryable":false}}""", 409) }
            repo.refresh(); repo.rename("Synthetic title"); val id = store.value.pending?.requestId
            repo.resolvePending(); repo.refresh()
            assertNotNull(id); assertEquals(id, store.value.pending?.requestId); assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `attachment only names remain visible across pending receipt and cleared editor sources`() = runBlocking {
        withRepository { repo, store, fixture, scope ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":true")
            repo.refresh()
            repo.saveAttachments("chat", listOf(dev.dshmobile.app.model.DraftAttachment("local", "content://synthetic/source", "/private/notes.txt", 4, "text/plain", "upload", Long.MAX_VALUE)))
            val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
            fixture.message = { request ->
                entered.complete(Unit); release.await(5, TimeUnit.SECONDS)
                json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""")
            }
            val send = scope.async { repo.sendMessage("", null, listOf("upload")) }
            try {
                withTimeout(5000) { entered.await() }
                assertEquals(listOf("notes.txt"), repo.state.value.pending?.attachmentNames)
            } finally { release.countDown() }
            send.await()
            assertNull(repo.state.value.pending)
            assertTrue(store.value.attachmentDrafts.isEmpty())
            assertEquals(listOf("notes.txt"), repo.state.value.acceptedPrompts.single().attachmentNames)
        }
    }
    @Test fun `discard staged uploads best effort but never delete admitted sources`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":true")
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
            repo.refresh()
            val source = dev.dshmobile.app.model.DraftAttachment("local", "content://synthetic/source", "test.txt", 4, "text/plain", "remove", Long.MAX_VALUE)
            repo.saveAttachments("chat", listOf(source)); repo.saveAttachments("chat", emptyList())
            kotlinx.coroutines.withTimeout(5000) { while (fixture.deletedUploads.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("remove"), fixture.deletedUploads.toList())
            repo.saveAttachments("chat", listOf(source.copy(uploadId = "admitted")))
            repo.sendMessage("", null, listOf("admitted"))
            repo.saveAttachments("chat", emptyList())
            assertEquals(listOf("remove"), fixture.deletedUploads.toList())
        }
    }
    @Test fun `missing staging refreshes once with same requestId and preserves payload`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":true")
            repo.refresh()
            val source = dev.dshmobile.app.model.DraftAttachment("local", "content://synthetic/source", "test.txt", 4, "text/plain", "old", Long.MAX_VALUE)
            repo.saveAttachments("chat", listOf(source))
            fixture.message = { request ->
                if (request.body.clone().readUtf8().contains("\"old\"")) json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""", 404)
                else json("""{"requestId":"${requestId(request)}","status":"accepted","updatedAt":20}""")
            }
            repo.sendMessage("", "queue", listOf("old"))
            assertEquals(2, fixture.mutations.size)
            assertEquals(1, fixture.uploadCount)
            assertEquals(1, fixture.mutations.map(::requestId).distinct().size)
            assertTrue(fixture.mutations.last().body.clone().readUtf8().contains("\"fresh-1\""))
            assertNull(store.value.pending)
            assertTrue(store.value.attachmentDrafts.isEmpty())
            repo.refresh(); assertEquals(2, fixture.mutations.size)
        }
    }
    @Test fun `explicit attachment only resend preserves uploads delivery and refreshes expired sources`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"attachments\":false", "\"attachments\":true")
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":20}""") }
            repo.refresh()
            repo.saveAttachments("chat", listOf(dev.dshmobile.app.model.DraftAttachment("local", "content://synthetic/source", "test.txt", 4, "text/plain", "old", 1)))
            repo.sendMessage("", "queue", listOf("old"))
            val original = store.value.acceptedPrompts.single().requestId
            repo.state.value.localPromptActions!!.resend(original)
            assertEquals(2, fixture.mutations.size)
            assertEquals(1, fixture.uploadCount)
            assertEquals(2, fixture.mutations.map(::requestId).distinct().size)
            val body = fixture.mutations.last().body.clone().readUtf8()
            assertTrue(body.contains("\"delivery\":\"queue\"")); assertTrue(body.contains("\"fresh-1\""))
            assertTrue(body.contains("\"text\":\"\""))
        }
    }
    @Test fun `pending receipt accepted becomes working within two seconds without waiting for snapshot`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"accepted","updatedAt":21}""") }
            fixture.chatSnapshot = { release.await(5, TimeUnit.SECONDS); json(snapshot()) }
            val send = scope.async { repo.sendMessage("Synthetic quick progress") }
            try {
                withTimeout(2000) { repo.state.first { it.pending == null && it.acceptedPrompts.isNotEmpty() } }
                assertEquals(1, fixture.mutations.size)
            } finally { release.countDown(); send.await() }
        }
    }
    @Test fun `pending answer and skip settle by receipt without another POST`() = runBlocking {
        for (skip in listOf(false, true)) withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"questions\":false", "\"questions\":true,\"questionSkip\":true")
            fixture.snapshotText = snapshot().dropLast(1) + """, "pendingQuestions":[{"callId":"call","state":"open","questions":[{"id":"q","question":"Synthetic?","options":[{"label":"Yes"}]}]}]}"""
            val answer = dev.dshmobile.app.model.QuestionAnswer(listOf(dev.dshmobile.app.model.AnswerItem("q", listOf("Yes"))))
            val polls = java.util.concurrent.atomic.AtomicInteger()
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"${if (polls.incrementAndGet() == 1) "pending" else "accepted"}","updatedAt":21}""") }
            repo.refresh(); repo.saveAnswerDraft("chat", "call", answer)
            if (skip) repo.skipQuestion("call") else repo.answer("call", answer)
            withTimeout(3000) { repo.state.first { it.pending == null } }
            assertNull(store.value.pending); assertTrue(store.value.answerDrafts.isEmpty())
            assertNull(repo.state.value.error); assertEquals(2, polls.get())
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `pending answer timeout stays uncertain with draft and manual rejection never resends`() = runBlocking {
        withRepository { repo, store, fixture, _ ->
            fixture.capabilities = fixture.capabilities.replace("\"questions\":false", "\"questions\":true")
            fixture.snapshotText = snapshot().dropLast(1) + """, "pendingQuestions":[{"callId":"call","state":"open","questions":[{"id":"q","question":"Synthetic?","options":[{"label":"Yes"}]}]}]}"""
            val answer = dev.dshmobile.app.model.QuestionAnswer(listOf(dev.dshmobile.app.model.AnswerItem("q", listOf("Yes"))))
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"pending","updatedAt":21}""") }
            repo.refresh(); repo.saveAnswerDraft("chat", "call", answer); repo.answer("call", answer)
            withTimeout(15_000) { repo.state.first { it.pending?.status == "uncertain" } }
            assertEquals("command_uncertain", repo.state.value.error); assertFalse(store.value.answerDrafts.isEmpty())
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"rejected","updatedAt":22}""") }
            repo.resolvePending(); assertNull(store.value.pending)
            assertEquals("command_rejected", repo.state.value.error); assertFalse(store.value.answerDrafts.isEmpty())
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `normal pending send auto polls accepted without ever becoming uncertain`() = runBlocking {
        withRepository { repo, _, fixture, scope ->
            val statuses = CopyOnWriteArrayList<String>()
            val collector = scope.launch(Dispatchers.Unconfined) { repo.state.collect { it.pending?.let { p -> statuses += p.status } } }
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            val polls = java.util.concurrent.atomic.AtomicInteger()
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"${if (polls.incrementAndGet() == 1) "pending" else "accepted"}","updatedAt":21}""") }
            try {
                repo.sendMessage("Synthetic auto-polled task")
                withTimeout(5000) { repo.state.first { it.pending == null && it.acceptedPrompts.isNotEmpty() } }
                assertEquals(2, polls.get())
                assertEquals(1, fixture.mutations.size)
                assertFalse(statuses.contains("uncertain"))
                assertTrue(statuses.contains("pending"))
            } finally { collector.cancel() }
        }
    }
    @Test fun `post send transport failure is uncertain and never replayed even with pending receipt`() = runBlocking {
        withRepository(preHeaderRetryDelay = { error("Post-header failures must never enter recovery") }) { repo, _, fixture, _ ->
            fixture.message = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"pending","updatedAt":21}""") }
            repo.sendMessage("Synthetic lost response")
            assertFalse(repo.state.value.sendNotSent)
            assertEquals("uncertain", repo.state.value.pending?.status)
            repo.resolvePending()
            assertEquals("uncertain", repo.state.value.pending?.status)
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `pending create publishes exact accepted identity through automatic receipt lookup`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.create = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"accepted","updatedAt":21,"result":{"sessionId":"other"}}""") }
            repo.createSession("workspace")
            val accepted = withTimeout(5000) { repo.acceptedCreate.first { it != null } }!!
            withTimeout(5000) { repo.state.first { it.pending == null && it.snapshot?.session?.id == "other" } }
            assertEquals(requestId(fixture.mutations.single()), accepted.requestId)
            assertEquals("other", accepted.sessionId)
        }
    }

    @Test fun `bounded polling exhausts to uncertain and manual check never replays`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            val polls = java.util.concurrent.atomic.AtomicInteger()
            fixture.receipt = { id -> polls.incrementAndGet(); json("""{"requestId":"$id","status":"pending","updatedAt":21}""") }
            repo.sendMessage("Synthetic unresolved receipt")
            withTimeout(15_000) { repo.state.first { it.pending?.status == "uncertain" } }
            assertEquals(6, polls.get())
            assertEquals("command_uncertain", repo.state.value.error)
            repo.resolvePending()
            assertEquals("uncertain", repo.state.value.pending?.status)
            assertEquals(1, fixture.mutations.size)
        }
    }
    @Test fun `abandoned pending create cannot publish a late auto polled acceptance`() = runBlocking {
        withRepository { repo, _, fixture, _ ->
            fixture.create = { json("""{"requestId":"${requestId(it)}","status":"pending","updatedAt":20}""", 202) }
            fixture.receipt = { id -> json("""{"requestId":"$id","status":"accepted","updatedAt":21,"result":{"sessionId":"other"}}""") }
            repo.createSession("workspace")
            repo.abandonPending()
            // A different command settling proves the abandoned request is not resurrected.
            fixture.message = { json("""{"requestId":"${requestId(it)}","status":"accepted","updatedAt":22}""") }
            repo.sendMessage("Synthetic different intent")
            repo.refresh()
            assertNull(repo.acceptedCreate.value)
            assertEquals("chat", repo.state.value.snapshot?.session?.id)
            assertEquals(2, fixture.mutations.size)
        }
    }

    @Test fun `review r2 chat reconnect replenishes exhausted automatic recovery budget`() = runBlocking {
        val retries = java.util.concurrent.CopyOnWriteArrayList<Int>()
        withRepository(retryDelay = { retries.add(it); 0L }) { repo, _, fixture, _ ->
            fixture.failIndex = true
            repo.refresh()
            withTimeout(5000) {
                while (retries.size < 6 || repo.state.value.connection != ConnectionState.OFFLINE) kotlinx.coroutines.delay(10)
            }
            kotlinx.coroutines.delay(100)
            assertEquals(listOf(0, 1, 2, 3, 4, 5), retries.toList())
            val requests = fixture.requests.count { it.path == "/v1/capabilities" }
            repo.selectSession("chat") // Automatic/navigation selection must NOT rearm the exhausted window.
            kotlinx.coroutines.delay(100)
            assertEquals(6, retries.size)
            repo.manualReconnect("chat") // The explicit callback's new production boundary.
            assertTrue("Chat reconnect must at least perform a direct fresh attempt", fixture.requests.count { it.path == "/v1/capabilities" } > requests)
            kotlinx.coroutines.delay(100)
            assertTrue("Explicit chat reconnect must grant a new recovery budget after a transient failed attempt", retries.size > 6)
            withTimeout(5000) { while (retries.size < 12 || repo.state.value.connection != ConnectionState.OFFLINE) kotlinx.coroutines.delay(10) }
            kotlinx.coroutines.delay(100)
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 5), retries.toList())
            assertFalse(repo.state.value.manualRefreshing)
            assertTrue(fixture.mutations.isEmpty())
            fixture.failIndex = false
            repo.manualReconnect("chat")
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            assertEquals("chat", repo.state.value.snapshot?.session?.id)
        }
    }

    private suspend fun withRepository(now: () -> Long = System::currentTimeMillis, preHeaderRetryDelay: (Int) -> Long = { 0L }, retryDelay: (Int) -> Long = { attempt -> val cap = minOf(30_000L, 1_000L shl minOf(attempt, 5)); kotlin.random.Random.nextLong(cap / 2, cap + 1) }, block: suspend (NetworkMobileRepository, MemoryStore, Fixture, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(kotlin.coroutines.coroutineContext + SupervisorJob())
        val store = MemoryStore()
        val fixture = Fixture()
        MockWebServer().use { server ->
            server.dispatcher = fixture
            server.start()
            fixture.stopServer = { val port = server.port; server.shutdown(); port }
            val repo = NetworkMobileRepository(store, scope, debug = true, now = now, retryDelay = retryDelay, preHeaderRetryDelay = preHeaderRetryDelay, openAttachment = { "test".byteInputStream() })
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
