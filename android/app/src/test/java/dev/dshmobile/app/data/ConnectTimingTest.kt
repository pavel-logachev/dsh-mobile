package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opt-in desktop JVM timing. Synthetic HTTP, fixed 100ms response latency; not phone rendering. */
class ConnectTimingTest {
    private class Store(var value: StoredState) : SecureStateStore {
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    @Test fun timings() = runBlocking {
        val output = System.getenv("DSH_CONNECT_TIMING_OUTPUT") ?: return@runBlocking
        val lines = mutableListOf("MEASURED JVM=${System.getProperty("java.version")} fixedResponseDelayMs=100 identityEncoding synthetic-loopback no-TLS no-render")
        repeat(5) { sample ->
            val paths = CopyOnWriteArrayList<String>()
            val bytes = AtomicLong()
            val refreshStart = AtomicLong()
            var measureRestore = false
            var offline = false
            val session = """{"id":"chat","title":"Synthetic 🌍","workspaceId":"workspace","updatedAt":10,"running":false,"canExecute":true}"""
            val snapshot = """{"session":$session,"messages":[{"id":"m","role":"assistant","text":"${"Текст 🌍 ".repeat(2000)}","createdAt":10}],"cursor":1,"hasMore":false,"activity":"idle"}"""
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        paths += path
                        if (measureRestore && path == "/v1/capabilities") refreshStart.compareAndSet(0, System.nanoTime())
                        if (path == "/v1/sessions" && offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        val body = when (path) {
                            "/v1/capabilities" -> """{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":false,"liveSnapshots":true,"attachments":false,"questions":false,"approvals":false,"push":false}}"""
                            "/v1/workspaces" -> """{"items":[{"id":"workspace","name":"Synthetic","canExecute":true}]}"""
                            "/v1/presets" -> """{"items":[]}"""
                            "/v1/sessions" -> {
                                val page = request.requestUrl!!.queryParameter("cursor")?.toInt() ?: 0
                                val item = session.replace("\"id\":\"chat\"", "\"id\":\"${if (page == 0) "chat" else "chat$page"}\"")
                                """{"items":[$item],"nextCursor":${if (page == 9) "null" else "\"${page + 1}\""}}"""
                            }
                            "/v1/sessions/chat/events" -> "event: snapshot\ndata: ${snapshot.replace("\"cursor\":1", "\"cursor\":2")}\n\n"
                            "/v1/sessions/chat" -> snapshot
                            else -> error("Unexpected synthetic route $path")
                        }
                        bytes.addAndGet(body.toByteArray(Charsets.UTF_8).size.toLong())
                        return MockResponse().setHeader("Content-Type", if (path.endsWith("events")) "text/event-stream" else "application/json")
                            .setBody(body + if (path.endsWith("events")) ": held\n\n" else "")
                            .setHeadersDelay(100, TimeUnit.MILLISECONDS)
                            .apply { if (path.endsWith("events")) throttleBody(body.toByteArray().size.toLong(), 1, TimeUnit.SECONDS) }
                    }
                }
                server.start()
                val store = Store(StoredState(host = PairedHost(HostEndpoint(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")), "device", "synthetic-token-for-tests", "Synthetic")))
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val repo = NetworkMobileRepository(store, scope, true, retryDelay = { 1000L }, observeRestoredSession = false)
                try {
                    val start = System.nanoTime()
                    repo.setForeground(true)
                    val onlineMs = (System.nanoTime() - start) / 1e6
                    val pagesAtOnline = paths.count { it == "/v1/sessions" }
                    // Drain background pages before measuring the independent chat-open path.
                    withTimeout(10000) { repo.state.first { it.sessions.size == 10 } }
                    val beforeRequests = paths.size; val beforeBytes = bytes.get()
                    val open = System.nanoTime()
                    repo.selectSession("chat")
                    val freshMs = (System.nanoTime() - open) / 1e6
                    assertNull("state error=${repo.state.value.error} paths=$paths", repo.state.value.error)
                    assertNotNull(repo.state.value.snapshot)
                    // Count both initial copies in the old GET->SSE path, not just the first GET.
                    withTimeout(5000) { repo.state.first { it.snapshot?.cursor == 2L } }
                    val streamFreshMs = (System.nanoTime() - open) / 1e6
                    lines += "sample=$sample launchOnlineMs=$onlineMs pagesAtOnline=$pagesAtOnline chatFreshMs=$freshMs streamFreshMs=$streamFreshMs chatRequests=${paths.size - beforeRequests} chatBodyBytes=${bytes.get() - beforeBytes} snapshotUtf8Bytes=${snapshot.toByteArray().size}"
                    repo.leaveSession()
                    offline = true
                    repo.refresh()
                    offline = false
                    measureRestore = true
                    val restored = System.nanoTime()
                    // Baseline has no network callback entry; candidate supplies wake through this optional public test seam.
                    val wake = repo.javaClass.methods.find { it.name.startsWith("networkRestored") && it.parameterCount == 0 }
                    wake?.invoke(repo)
                    withTimeout(5000) { while (refreshStart.get() == 0L) delay(1) }
                    lines += "sample=$sample restoredToRefreshMs=${(refreshStart.get() - restored) / 1e6} wakeAvailable=${wake != null} postCount=${paths.count { it.endsWith("messages") }}"
                } finally { repo.close(); scope.cancel() }
            }
        }
        File(output).writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        println(lines.joinToString("\n"))
    }
}
