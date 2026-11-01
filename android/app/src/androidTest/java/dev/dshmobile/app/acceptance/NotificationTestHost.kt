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

/** Every navigation journey owns a fresh synthetic question; no shared fixture mutation or visible Activity. */
internal class NotificationTestHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = MockWebServer()
    @Volatile private var resolved = false
    @Volatile var historyDelayMs = 0L
    @Volatile var twoChats = false
    @Volatile var failOtherHistory = false
    val historyRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
    init {
        cleanup()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":true,"canExecute":true}"""
                val question = """{"callId":"question","state":"open","questions":[{"id":"q","question":"Which synthetic check?","options":[{"label":"Unit tests"}],"multiSelect":true}]}"""
                fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
                if (request.requestUrl!!.encodedPath in setOf("/v1/sessions/chat", "/v1/sessions/other")) historyRequests += request.requestUrl!!.encodedPath
                return when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":true,"approvals":false,"push":false,"promptOutcomes":true}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> json("""{"items":[$session${if (twoChats) "," + session.replace("\"id\":\"chat\"", "\"id\":\"other\"") else ""}],"nextCursor":null}""")
                    "/v1/sessions/other" -> if (failOtherHistory) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json("""{"session":${session.replace("\"id\":\"chat\"", "\"id\":\"other\"")},"messages":[],"cursor":2,"hasMore":false,"activity":"idle"}""")
                    "/v1/sessions/chat" -> json("""{"session":$session,"messages":[],"cursor":1,"hasMore":false,"activity":"waiting","pendingQuestions":${if (resolved) "[]" else "[$question]"},"resolvedQuestions":${if (resolved) """[{"callId":"question","outcome":"answered","answers":[{"id":"q","selected":["Unit tests"]}]}]""" else "[]"}}""").setBodyDelay(historyDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    "/v1/sessions/chat/answers" -> { resolved = true; json("""{"requestId":"${JSONObject(request.body.readUtf8()).getString("requestId")}","status":"accepted","updatedAt":20}""") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking {
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")), "synthetic", "synthetic-notification-token-00000000", "Synthetic")))
            NotificationStore(context).update { NotificationLocal(enabled = true, deviceId = "synthetic", watches = emptyList()) }
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
