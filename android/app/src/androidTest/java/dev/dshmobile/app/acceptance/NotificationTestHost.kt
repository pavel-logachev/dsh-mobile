package dev.dshmobile.app.acceptance

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject

/** Every journey owns a fresh feed epoch/question; no shared fixture mutation or visible Activity. */
internal class NotificationTestHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = MockWebServer()
    @Volatile private var resolved = false
    @Volatile var historyDelayMs = 0L
    @Volatile var namesDelayMs = 0L
    @Volatile var blocked = false
    @Volatile var twoChats = false
    @Volatile var failOtherHistory = false
    val historyRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var slowEnrichment = false
    private val snapshotCalls = java.util.concurrent.atomic.AtomicInteger()
    val enrichmentEntered = java.util.concurrent.CountDownLatch(1)
    init {
        cleanup()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":true,"canExecute":true}"""
                val question = """{"callId":"question","state":"open","questions":[{"id":"q","question":"Which synthetic check?","options":[{"label":"Unit tests"}],"multiSelect":true}]}"""
                fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
                if (request.requestUrl!!.encodedPath in setOf("/v1/sessions/chat", "/v1/sessions/other")) historyRequests += request.requestUrl!!.encodedPath
                return when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":true,"approvals":false,"push":false,"notifications":true}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""").setBodyDelay(namesDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> json("""{"items":[$session${if (twoChats) "," + session.replace("\"id\":\"chat\"", "\"id\":\"other\"") else ""}],"nextCursor":null}""")
                    "/v1/sessions/other" -> if (failOtherHistory) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json("""{"session":${session.replace("\"id\":\"chat\"", "\"id\":\"other\"")},"messages":[],"cursor":2,"hasMore":false,"activity":"idle"}""")
                    "/v1/sessions/chat" -> { val first = snapshotCalls.incrementAndGet() == 1; if (slowEnrichment && first) enrichmentEntered.countDown(); json("""{"session":$session,"messages":[],"cursor":1,"hasMore":false,"activity":"waiting","promptOutcomes":[{"requestId":"00000000-0000-4000-8000-000000000001","turnId":"synthetic-turn","status":"waiting"}],"pendingQuestions":${if (resolved) "[]" else "[$question]"},"resolvedQuestions":${if (resolved) """[{"callId":"question","outcome":"answered","answers":[{"id":"q","selected":["Unit tests"]}]}]""" else "[]"}}""").setBodyDelay(if (slowEnrichment && first) 5000 else historyDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    "/v1/sessions/chat/answers" -> { resolved = true; json("""{"requestId":"${JSONObject(request.body.readUtf8()).getString("requestId")}","status":"accepted","updatedAt":20}""") }
                    "/v1/notification-settings" -> json("""{"revision":1,"enabled":true,"projects":[],"chats":[]}""")
                    "/v1/notification-events/stream" -> {
                        if (blocked) return MockResponse().setResponseCode(401)
                        val initial = "event: notification-page\ndata: {\"version\":1,\"epoch\":\"synthetic\",\"items\":[],\"nextCursor\":\"cursor\",\"hasMore\":false,\"resetRequired\":true,\"coverage\":\"ready\",\"pending\":[{\"sessionId\":\"chat\",\"workspaceId\":\"w\",\"attentionId\":\"attention\"}]}\n\n"
                        val answer = "event: notification-page\ndata: {\"version\":1,\"epoch\":\"synthetic\",\"items\":[{\"version\":1,\"eventId\":\"answer-event\",\"sequence\":1,\"occurredAt\":1,\"expiresAt\":9223372036854775807,\"workspaceId\":\"w\",\"sessionId\":\"other\",\"kind\":\"answer-finished\",\"sourceSeq\":1,\"turn\":1}],\"nextCursor\":\"cursor2\",\"hasMore\":false,\"resetRequired\":false,\"coverage\":\"ready\",\"pending\":[]}\n\n"
                        val prefix = initial + if (slowEnrichment) answer else ""
                        // A finite KEEP_OPEN body still reports SSE onClosed at Content-Length.
                        // Throttle a large heartbeat tail instead, keeping a real active stream.
                        MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody(prefix + ": heartbeat\n\n".repeat(100000))
                            .throttleBody(prefix.toByteArray().size.toLong() + 16, 1, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking {
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")), "synthetic", "synthetic-notification-token-00000000", "Synthetic")))
            NotificationStore(context).update { NotificationLocal(enabled = true, deviceId = "synthetic", settings = NotificationSettings(enabled = true), dirty = true, channelModesMigrated = true) }
        }
    }
    private fun cleanup() {
        runBlocking { NotificationStore(context).update { it.copy(enabled = false) } }
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it).toList() }.filterIsInstance<MainActivity>().forEach { it.finishAndRemoveTask() }
            context.stopService(Intent(context, NotificationService::class.java))
        }
        instrumentation.waitForIdleSync()
        VisibleNotificationChat.sessionId = null
        NotificationPosting.cancelAll(context)
    }
    override fun close() { cleanup(); server.shutdown() }
    fun closeScenario(scenario: ActivityScenario<MainActivity>) {
        // ActivityScenario matches lifecycle by the launch Intent; production correctly
        // replaces it on SINGLE_TOP delivery. Restore only the harness identity for teardown.
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it).toList() }
                .filterIsInstance<MainActivity>().forEach { it.intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java); it.finishAndRemoveTask() }
        }
        scenario.close()
    }
}
