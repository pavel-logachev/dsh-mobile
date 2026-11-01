package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ReviewLocaleRegressionTest {
    @Test fun localeChangeRebuildsTitleBodyBigTextAndUnnamedFallback() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = NotificationStore(MemoryWatchRecord(), StateCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 5 }, "AES") })
        val manager = app.getSystemService(NotificationManager::class.java)
        ensureNotificationChannels(app)
        for (kind in listOf("error", "attention", "answer")) {
            app.getSharedPreferences("mobile_language", 0).edit().putString("language", "ru").commit()
            val alert = WatchAlert("key-$kind", "chat", "turn", kind, "", "Synthetic failure 😀\nline", Long.MAX_VALUE)
            store.update { NotificationLocal(deviceId = "device", outbox = listOf(alert)) }
            NotificationPosting.recordForegroundPost()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            postWatchAlert(app, "device", alert, false, store)
            val old = manager.activeNotifications.single { it.id == 2 }.notification
            app.getSharedPreferences("mobile_language", 0).edit().putString("language", "en").commit()
            val next = translatedNotification(app, 2, "device:phone-chat:chat", old)!!
            val title = when (kind) { "error" -> "Task failed"; "attention" -> "Response needed"; else -> "Answer ready" }
            val body = if (kind == "error") "Untitled chat · Synthetic failure 😀\nline" else "Untitled chat"
            assertEquals(title, next.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(body, next.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(body, next.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            assertEquals(title, next.publicVersion.extras.getString(Notification.EXTRA_TEXT))
            assertTrue(next.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            System.getenv("DSH_ANDROID_RENDER_DIR")?.let { output ->
                val remote = Notification.Builder.recoverBuilder(app, next).createBigContentView()
                val view = remote.apply(app, android.widget.FrameLayout(app))
                val width = 480
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val image = android.graphics.Bitmap.createBitmap(width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(image)
                canvas.drawColor(android.graphics.Color.WHITE)
                view.draw(canvas)
                java.io.File(output, "notification-en-$kind.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
