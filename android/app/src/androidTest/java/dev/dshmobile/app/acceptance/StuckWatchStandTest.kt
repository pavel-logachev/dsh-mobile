package dev.dshmobile.app.acceptance

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Opt-in disposable stand stages; private cache config is provided only on the emulator. */
class StuckWatchStandTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val store get() = NotificationStore(context)
    private suspend fun waitFor(check: suspend () -> Boolean) = withTimeout(15000) {
        while (!check()) delay(100)
    }
    @Test fun stage(): Unit = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("watchStage") ?: return@runBlocking
        when (stage) {
            "journey" -> {
                val file = java.io.File(context.cacheDir, "watch-stand.json")
                val config = JSONObject(file.readText()); file.delete()
                val host = PairedHost(HostEndpoint(config.getString("baseUrl")), config.getString("deviceId"),
                    config.getString("deviceToken"), "Synthetic watch stand")
                val session = config.getString("sessionId")
                EncryptedStateStore(context).write(StoredState(host = host, selectedSessionId = session))
                store.update { NotificationLocal(enabled = true) }
                suspend fun control(action: String) = withContext(Dispatchers.IO) {
                    val connection = java.net.URL("http://127.0.0.1:${config.getInt("controlPort")}/$action").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10000; connection.readTimeout = 10000
                    try { assertEquals(200, connection.responseCode); connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
                }
                suspend fun send() {
                    val id = UUID.randomUUID().toString()
                    registerPhoneWatch(context, host.deviceId, session, id, "Synthetic watch")
                    val api = HostApi(host.endpoint, host.deviceToken)
                    try { assertEquals("accepted", api.command(StoredCommand(id, "send", session,
                        "DSH_MOBILE_CANARY_MOBILE: synthetic prompt through the real companion adapter.", delivery = "queue")).status) }
                    finally { api.closeAndAwait() }
                    waitFor { manager.activeNotifications.any { it.id == 1 } }
                }
                suspend fun capture(name: String) {
                    instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
                    delay(700)
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    for ((kind, command) in listOf("notification" to "dumpsys notification --noredact", "services" to "dumpsys activity services dev.dshmobile.app")) {
                        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            java.io.File(context.getExternalFilesDir(null), "$name-$kind.txt").outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                control("start"); send(); control("remove")
                waitFor { store.read().watches.isEmpty() && !serviceRunning() }
                assertTrue(manager.activeNotifications.none { it.id == 1 }); assertTrue(store.read().outbox.isEmpty())
                capture("fixed-removed")
                send(); control("complete")
                waitFor { store.read().watches.isEmpty() && !serviceRunning() && manager.activeNotifications.any {
                    it.notification.extras.getString("android.title") == context.getString(dev.dshmobile.app.R.string.notif_answer_ready) } }
                assertTrue(manager.activeNotifications.none { it.id == 1 }); capture("fixed-answer")
            }
            "send", "normal" -> {
                if (stage == "send") {
                    val file = java.io.File(context.cacheDir, "watch-stand.json")
                    val config = JSONObject(file.readText()); file.delete()
                    EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint(config.getString("baseUrl")),
                        config.getString("deviceId"), config.getString("deviceToken"), "Synthetic watch stand"),
                        selectedSessionId = config.getString("sessionId")))
                    store.update { NotificationLocal(enabled = true) }
                }
                val state = EncryptedStateStore(context).read()
                val host = requireNotNull(state.host)
                val session = requireNotNull(state.selectedSessionId)
                val request = UUID.randomUUID().toString()
                registerPhoneWatch(context, host.deviceId, session, request, "Synthetic watch")
                val api = HostApi(host.endpoint, host.deviceToken)
                try {
                    val receipt = api.command(StoredCommand(request, "send", session,
                        "DSH_MOBILE_CANARY_MOBILE: synthetic prompt through the real companion adapter.", delivery = "queue"))
                    assertTrue(receipt.status in setOf("accepted", "pending"))
                } finally { api.closeAndAwait() }
                waitFor { manager.activeNotifications.any { it.id == 1 } }
                assertEquals(1, store.read().watches.size)
            }
            "stuck" -> {
                assertEquals(1, store.read().watches.size)
                assertTrue(manager.activeNotifications.any { it.id == 1 })
                assertTrue(serviceRunning())
            }
            "restored" -> {
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitFor { store.read().watches.isEmpty() && !serviceRunning() }
                }
                assertTrue(manager.activeNotifications.none { it.id == 1 })
                assertTrue(store.read().outbox.isEmpty())
            }
            "cleared", "answer" -> {
                waitFor { store.read().watches.isEmpty() && !serviceRunning() }
                assertTrue(manager.activeNotifications.none { it.id == 1 })
                assertTrue(store.read().outbox.isEmpty())
                if (stage == "answer") assertTrue(manager.activeNotifications.any {
                    it.notification.extras.getString("android.title") == context.getString(dev.dshmobile.app.R.string.notif_answer_ready)
                })
            }
            else -> error("Unknown stand stage")
        }
    }
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == NotificationService::class.java.name }
}
