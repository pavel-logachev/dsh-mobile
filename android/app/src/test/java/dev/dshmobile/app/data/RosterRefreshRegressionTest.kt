package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Public HTTP boundary regressions for review F1/F3 and the capped supplement. */
class RosterRefreshRegressionTest {
    private class Store : SecureStateStore {
        var value = StoredState()
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    private fun json(text: String, status: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(status).setBody(text)
    private fun row(id: String, running: Boolean = false, time: Int = 10, workspace: String = "workspace") = """{"id":"$id","title":"Synthetic $id","workspaceId":"$workspace","updatedAt":$time,"running":$running,"canExecute":true}"""
    private suspend fun fixture(index: (RecordedRequest) -> MockResponse, workspaces: String = """{"items":[{"id":"workspace","name":"Synthetic","canExecute":true}]}""",
        disk: HistoryStore = MemoryHistoryStore(), run: suspend (NetworkMobileRepository, CopyOnWriteArrayList<RecordedRequest>) -> Unit) {
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.requestUrl!!.encodedPath) {
                        "/v1/pairings" -> json("""{"deviceId":"fixture-device","deviceToken":"synthetic-device-token-for-tests","hostName":"Synthetic","protocolVersion":1}""", 201)
                        "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}""")
                        "/v1/workspaces" -> json(workspaces)
                        "/v1/presets" -> json("""{"items":[]}""")
                        "/v1/sessions" -> index(request)
                        else -> json("{}", 404)
                    }
                }
            }
            server.start()
            val repo = NetworkMobileRepository(Store(), scope, debug = true, observeRestoredSession = false, prefetchEnabled = false,
                indexRefreshIntervalMs = 100, retryDelay = { 60_000 }, historyStore = disk)
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-pairing-token-for-tests"}""", "Synthetic")
                run(repo, requests)
            } finally { repo.close(); scope.cancel() }
        }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }
    private fun lists(requests: List<RecordedRequest>) = requests.filter { it.requestUrl!!.encodedPath == "/v1/sessions" }

    @Test fun `complete tick removes deleted or archived rows from state and disk`() = runBlocking {
        val removed = AtomicBoolean(false); val disk = MemoryHistoryStore()
        fixture({ _ -> json("""{"items":[${row("keep")}${if (!removed.get()) "," + row("deleted") + "," + row("archived") else ""}],"activeItems":[],"activeItemsTruncated":false,"nextCursor":null}""") }, disk = disk) { repo, requests ->
            assertEquals(3, repo.state.value.sessions.size)
            removed.set(true)
            await { lists(requests).size >= 3 }; repo.setForeground(false)
            assertEquals(listOf("keep"), repo.state.value.sessions.map { it.id })
            assertFalse("Complete host roster must remain complete", repo.state.value.sessionsTruncated)
            assertEquals(listOf("keep"), disk.read().index!!.sessions.map { it.id })
            assertFalse(disk.read().index!!.truncated)
        }
    }

    @Test fun `complete authorized tick removes rows from narrowed grants`() = runBlocking {
        val narrowed = AtomicBoolean(false); val disk = MemoryHistoryStore()
        fixture({ _ -> json("""{"items":[${row("keep")}${if (!narrowed.get()) "," + row("previously-granted", workspace = "beta") else ""}],"activeItems":[],"activeItemsTruncated":false,"nextCursor":null}""") },
            workspaces = """{"items":[{"id":"workspace","name":"Synthetic","canExecute":true},{"id":"beta","name":"Beta","canExecute":true}]}""", disk = disk) { repo, requests ->
            assertTrue(repo.state.value.sessions.any { it.workspaceId == "beta" })
            narrowed.set(true)
            await { lists(requests).size >= 3 }; repo.setForeground(false)
            assertEquals(listOf("keep"), repo.state.value.sessions.map { it.id })
            assertFalse(repo.state.value.sessionsTruncated)
            assertEquals(listOf("keep"), disk.read().index!!.sessions.map { it.id })
        }
    }

    @Test fun `incomplete tick retains only rows below date page cut not supplemental cut`() = runBlocking {
        val changed = AtomicBoolean(false)
        fixture({ _ -> if (!changed.get()) json("""{"items":[${listOf(row("top", time = 100), row("removed-above", time = 90), row("a", time = 50), row("z", time = 50), row("older", time = 40)).joinToString(",")}],"activeItems":[],"nextCursor":null}""")
            else json("""{"items":[${row("top", time = 100)},${row("m", time = 50)}],"activeItems":[${row("ancient-active", true, 1)}],"activeItemsTruncated":false,"nextCursor":"more"}""")
        }) { repo, requests ->
            changed.set(true)
            await { lists(requests).size >= 3 }; repo.setForeground(false)
            assertEquals(setOf("top", "m", "z", "older", "ancient-active"), repo.state.value.sessions.map { it.id }.toSet())
            assertTrue(repo.state.value.sessionsTruncated)
            assertEquals(0, lists(requests).count { it.requestUrl!!.queryParameter("cursor") != null })
        }
    }

    @Test fun `complete date roster is authoritative independently of supplement coverage`() = runBlocking {
        for (supplement in listOf("", ",\"activeItems\":[],\"activeItemsTruncated\":true", ",\"activeItems\":[${row("fresh-active", true, 1)}],\"activeItemsTruncated\":false")) {
            val changed = AtomicBoolean(false)
            fixture({ _ -> if (!changed.get()) json("""{"items":[${row("keep")},${row("removed")}],"nextCursor":null}""")
                else json("""{"items":[${row("keep")}],"nextCursor":null$supplement}""")
            }) { repo, requests ->
                changed.set(true)
                await { lists(requests).size >= 3 }; repo.setForeground(false)
                val expected = if (supplement.contains("fresh-active")) setOf("keep", "fresh-active") else setOf("keep")
                assertEquals(expected, repo.state.value.sessions.map { it.id }.toSet())
                assertFalse(repo.state.value.sessionsTruncated)
            }
        }
    }

    @Test fun `complete empty tick replaces formerly truncated window`() = runBlocking {
        val empty = AtomicBoolean(false)
        fixture({ request -> if (empty.get()) json("""{"items":[],"activeItems":[],"nextCursor":null}""") else {
            val page = request.requestUrl!!.queryParameter("cursor")?.toInt() ?: 0
            json("""{"items":[${row("row-$page", time = 100 - page)}],"activeItems":[],"nextCursor":"${page + 1}"}""")
        } }) { repo, requests ->
            await { repo.state.value.sessions.size == 10 }
            assertTrue(repo.state.value.sessionsTruncated)
            empty.set(true)
            await { lists(requests).count { it.requestUrl!!.queryParameter("cursor") == null } >= 3 }
            repo.setForeground(false)
            assertTrue(repo.state.value.sessions.isEmpty())
            assertFalse(repo.state.value.sessionsTruncated)
        }
    }

    @Test fun `ticks fetch first page only and retain loaded older rows`() = runBlocking {
        fixture({ request ->
            val page = request.requestUrl!!.queryParameter("cursor")?.toInt() ?: 0
            json("""{"items":[${row("row-$page")}],"activeItems":[],"nextCursor":"${page + 1}"}""")
        }) { repo, requests ->
            await { repo.state.value.sessions.size == 10 }
            await { lists(requests).count { it.requestUrl!!.queryParameter("cursor") == null } >= 3 }
            repo.setForeground(false)
            assertEquals("Only initial load traverses pages", 9, lists(requests).count { it.requestUrl!!.queryParameter("cursor") != null })
            assertEquals(10, repo.state.value.sessions.size)
        }
    }

    @Test fun `finished supplemental chat beyond loaded window stays idle with last known metadata`() = runBlocking {
        val running = AtomicBoolean(true)
        fixture({ request ->
            val page = request.requestUrl!!.queryParameter("cursor")?.toInt() ?: 0
            val items = (page * 100 until page * 100 + 100).joinToString(",") { row("idle-$it", time = 10000 - it) }
            val active = if (running.get()) row("old-active", true, 1).replace("\"canExecute\":true", "\"runningSubagents\":2,\"waitingForAnswer\":true,\"canExecute\":true") else ""
            json("""{"items":[$items],"activeItems":[$active],"activeItemsTruncated":false,"nextCursor":"${page + 1}"}""")
        }) { repo, requests ->
            await { lists(requests).any { it.requestUrl!!.queryParameter("cursor") == "9" } && repo.state.value.sessions.size >= 1000 }
            assertTrue(repo.state.value.sessions.any { it.id == "old-active" && it.running })
            val before = repo.state.value.sessions.map { it.id }.toSet()
            running.set(false)
            await { lists(requests).count { it.requestUrl!!.queryParameter("cursor") == null } >= 3 }
            delay(30)
            val finished = repo.state.value.sessions.find { it.id == "old-active" }
            assertNotNull("Finished supplemental row must not disappear", finished)
            assertFalse("Absence from complete active set clears activity", finished!!.running)
            assertFalse(finished.waitingForAnswer); assertEquals(0, finished.runningSubagents)
            assertEquals(1L, finished.updatedAt); assertEquals("Synthetic old-active", finished.title)
            assertTrue(repo.state.value.sessions.map { it.id }.containsAll(before))
        }
    }

    @Test fun `absent legacy field and truncated supplement never clear unknown activity`() = runBlocking {
        for (supplement in listOf("", ",\"activeItems\":[],\"activeItemsTruncated\":true")) {
            val initial = AtomicBoolean(true)
            fixture({ _ -> if (initial.get()) json("""{"items":[${row("known")}],"activeItems":[${row("old-active", true, 1)}],"nextCursor":null}""")
                else json("""{"items":[${row("known")}],"nextCursor":"more"$supplement}""")
            }) { repo, requests ->
                initial.set(false)
                await { lists(requests).size >= 3 }; delay(30)
                assertTrue(repo.state.value.sessions.single { it.id == "old-active" }.running)
            }
        }
    }

    @Test fun `background cancels held tick and prevents late publication`() = runBlocking {
        val hold = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        fixture({ _ ->
            if (hold.get()) { entered.complete(Unit); release.await(5, java.util.concurrent.TimeUnit.SECONDS); json("""{"items":[${row("late")}],"activeItems":[],"nextCursor":null}""") }
            else json("""{"items":[${row("original")}],"activeItems":[],"nextCursor":null}""")
        }) { repo, requests ->
            hold.set(true)
            withTimeout(5000) { entered.await() }
            try { withTimeout(1000) { repo.setForeground(false) } } finally { release.countDown() }
            val count = lists(requests).size; delay(250)
            assertEquals(count, lists(requests).size)
            assertEquals(listOf("original"), repo.state.value.sessions.map { it.id })
            assertEquals(ConnectionState.OFFLINE, repo.state.value.connection)
        }
    }

    @Test fun `failed tick preserves online state and backs off honoring Retry After`() = runBlocking {
        val error = AtomicBoolean(false)
        fixture({ _ -> if (error.get()) json("""{"error":{"code":"rate_limited","message":"Synthetic","retryable":true}}""", 429).setHeader("Retry-After", "2")
            else json("""{"items":[${row("idle")}],"activeItems":[],"nextCursor":null}""")
        }) { repo, requests ->
            error.set(true)
            await { lists(requests).size >= 2 }; delay(30)
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            val count = lists(requests).size
            delay(350)
            assertEquals("No tight retry/reconnect after failed optional tick", count, lists(requests).size)
        }
    }
}
