package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

class ReviewIndexRegressionTest {
    private class Store : SecureStateStore {
        var value = StoredState()
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    private fun session(id: String) = """{"id":"$id","title":"Synthetic $id","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
    private fun snapshot(id: String) = """{"session":${session(id)},"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}"""
    private fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
    @Test fun lateBackgroundIndexPreservesPointSelectionAndFreshRename() = runBlocking {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        var renamed = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false,"rename":true}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> if (request.requestUrl!!.queryParameter("cursor") != null) {
                        entered.complete(Unit); release.await(10, TimeUnit.SECONDS)
                        json("""{"items":[${session("old-second")},${session("point")}],"nextCursor":null}""")
                    } else json("""{"items":[${session("old-first")}],"nextCursor":"next"}""")
                    "/v1/sessions/point" -> json(if (renamed) snapshot("point").replace("Synthetic point", "Fresh title") else snapshot("point"))
                    "/v1/sessions/point/title" -> {
                        renamed = true
                        val id = mobileJson.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("requestId").jsonPrimitive.content
                        json("""{"requestId":"$id","status":"accepted","updatedAt":1}""")
                    }
                    else -> json("{}")
                }
            }
            server.start()
            store.value = StoredState(host = PairedHost(HostEndpoint(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")), "device", "synthetic-test-token", "Synthetic"))
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = NetworkMobileRepository(store, scope, true, observeRestoredSession = false)
            try {
                repo.setForeground(true); withTimeout(5000) { entered.await() }
                repo.selectSession("point")
                assertEquals("selection error: ${repo.state.value.error}", "point", repo.state.value.snapshot?.session?.id)
                repo.rename("Fresh title")
                assertEquals("Fresh title", repo.state.value.snapshot?.session?.title)
                release.countDown()
                withTimeout(5000) { repo.state.first { it.sessions.any { s -> s.id == "old-second" } && !it.sessionsTruncated } }
                assertEquals("point", store.value.selectedSessionId)
                assertEquals("Fresh title", repo.state.value.sessions.single { it.id == "point" }.title)
                assertEquals("Fresh title", repo.state.value.snapshot?.session?.title)
            } finally { release.countDown(); repo.close(); scope.cancel() }
        }
    }
    @Test fun lateBackgroundIndexPreservesAcceptedSessionSelectionAndCache() = runBlocking {
        val store = Store(); val disk = MemoryHistoryStore()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        var created = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> when {
                        request.method == "POST" -> {
                            created = true
                            val id = mobileJson.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("requestId").jsonPrimitive.content
                            json("""{"requestId":"$id","status":"accepted","updatedAt":1,"result":{"sessionId":"created"}}""")
                        }
                        request.requestUrl!!.queryParameter("cursor") != null -> {
                            entered.complete(Unit); release.await(10, TimeUnit.SECONDS)
                            json("""{"items":[${session("old-second")}],"nextCursor":null}""")
                        }
                        created -> json("""{"items":[${session("old-first")},${session("created")}],"nextCursor":null}""")
                        else -> json("""{"items":[${session("old-first")}],"nextCursor":"next"}""")
                    }
                    "/v1/sessions/created" -> json(snapshot("created"))
                    else -> json("{}")
                }
            }
            server.start()
            store.value = StoredState(host = PairedHost(HostEndpoint(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")), "device", "synthetic-device-token-for-tests", "Synthetic"))
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val repo = NetworkMobileRepository(store, scope, true, observeRestoredSession = false, historyStore = disk)
            try {
                repo.setForeground(true); withTimeout(5000) { entered.await() }
                repo.createSession("w")
                assertEquals("created", store.value.selectedSessionId)
                assertEquals("created", repo.state.value.snapshot?.session?.id)
                assertTrue(repo.state.value.sessions.any { it.id == "created" })
                release.countDown()
                withTimeout(5000) { repo.state.first { it.sessions.any { s -> s.id == "old-second" } && !it.sessionsTruncated } }
                assertEquals("created", store.value.selectedSessionId)
                assertEquals("created", repo.state.value.snapshot?.session?.id)
                assertTrue(repo.state.value.sessions.any { it.id == "created" })
                assertEquals("created", repo.acceptedCreate.value?.sessionId)
                repo.setForeground(false) // Flush durable cache after the late page has published.
                assertTrue(disk.read().entries.any { it.snapshot.session.id == "created" })
                assertTrue(disk.read().index!!.sessions.any { it.id == "created" })
            } finally { release.countDown(); repo.close(); scope.cancel() }
        }
    }
}
