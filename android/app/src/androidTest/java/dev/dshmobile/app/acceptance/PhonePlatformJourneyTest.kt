package dev.dshmobile.app.acceptance

import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real OS pickers/camera/chooser and MediaStore. Synthetic data + emulator only. */
class PhonePlatformJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun configure() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val file = File(context.filesDir, "phone08-config.json")
        assumeTrue("Isolated synthetic fixture required", file.exists())
        val config = JSONObject(file.readText())
        runBlocking { EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:${config.getInt("port")}"), config.getString("deviceId"), config.getString("deviceToken"), "Synthetic fixture"))) }
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = android.os.SystemClock.uptimeMillis() + 10000
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        while (android.os.SystemClock.uptimeMillis() < end) {
            find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            instrumentation.uiAutomation.waitForIdle(100, 500)
        }
        val nodes = mutableListOf<String>()
        fun dump(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            nodes += "${node.viewIdResourceName} | ${node.text} | ${node.contentDescription} | $bounds | selected=${node.isSelected} clickable=${node.isClickable}"
            for (i in 0 until node.childCount) dump(node.getChild(i))
        }
        dump(instrumentation.uiAutomation.rootInActiveWindow)
        error("Synthetic platform node not found: " + nodes.joinToString("\n"))
    }
    private fun clickSystem(vararg labels: String) {
        val matches: (AccessibilityNodeInfo) -> Boolean = { n -> n.isVisibleToUser && labels.any { n.text?.toString()?.contains(it, true) == true || n.contentDescription?.toString()?.contains(it, true) == true } }
        awaitNode(matches)
        instrumentation.uiAutomation.waitForIdle(500, 3000)
        val original = awaitNode(matches)
        // System Compose surfaces sometimes acknowledge ACTION_CLICK without dispatching it.
        // Tap the bounds discovered from accessibility, never hard-coded screen coordinates.
        val bounds = android.graphics.Rect(); original.getBoundsInScreen(bounds)
        dev.dshmobile.app.data.notificationTrace("system_tap_bounds=$bounds")
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            File(context.filesDir, "system-before-tap.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun openChat() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("session_chat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session_chat").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun attach(label: String) {
        compose.onNodeWithContentDescription(context.getString(dev.dshmobile.app.R.string.mobile_attach)).performClick()
        compose.onNodeWithText(label).performClick()
    }
    @Test fun temporaryExportExpiryAndCameraCleanup() {
        val dir = dev.dshmobile.app.ui.chat.TemporaryMedia.directory(context)
        val share = File(dir, "share-synthetic-expired.txt").apply { writeText("Synthetic"); setLastModified(System.currentTimeMillis() - dev.dshmobile.app.ui.chat.TemporaryMedia.SHARE_TTL_MS - 1) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", share)
        try { context.contentResolver.openInputStream(uri)?.close(); fail("Expired export opened") } catch (_: java.io.FileNotFoundException) { }
        // Provider refuses expired shares; draft-aware cleanup owns deletion/revocation.
        dev.dshmobile.app.ui.chat.TemporaryMedia.cleanupShares(context)
        assertFalse(share.exists())
        val camera = File(dir, "camera-synthetic-orphan.jpg").apply { writeText("Synthetic"); setLastModified(System.currentTimeMillis() - dev.dshmobile.app.ui.chat.TemporaryMedia.CAMERA_TTL_MS - 1) }
        dev.dshmobile.app.ui.chat.TemporaryMedia.cleanup(context, emptyList())
        assertFalse(camera.exists())
    }
    @Test fun photoPickerUploadsAndSends() {
        configure()
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "phone08-synthetic.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        })!!
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try { ActivityScenario.launch(MainActivity::class.java).use {
            openChat(); attach(context.getString(dev.dshmobile.app.R.string.mobile_attach_photos))
            // API 30 uses DocumentsUI fallback rather than the platform Photo Picker.
            if (android.os.Build.VERSION.SDK_INT < 33) {
                clickSystem("Show roots", "Показать корни", "Открыть список")
                fun clickDocumentEntry(labels: List<String>, folder: Boolean = false) {
                    instrumentation.uiAutomation.waitForIdle(500, 3000)
                    val entry = awaitNode { node ->
                        var parent = node.parent
                        while (parent != null && parent.viewIdResourceName?.endsWith(":id/item_root") != true) parent = parent.parent
                        node.isVisibleToUser && node.viewIdResourceName?.endsWith(":id/title") == true && node.text?.toString() in labels && (!folder || parent != null)
                    }
                    var target = entry
                    if (folder) {
                        while (target.viewIdResourceName?.endsWith(":id/item_root") != true) target = requireNotNull(target.parent)
                        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        return
                    }
                    val bounds = android.graphics.Rect(); target.getBoundsInScreen(bounds)
                    instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                    }
                }
                clickDocumentEntry(listOf("Images", "Изображения"))
                clickDocumentEntry(listOf("Pictures"), folder = true)
            }
            // Do not match explanatory text «доступ ... фотографиям» as a photo tile.
            val photo = awaitNode { node -> node.isVisibleToUser && (node.text?.toString() == "phone08-synthetic.png" || node.contentDescription?.startsWith("phone08-synthetic.png,") == true || node.contentDescription?.startsWith("Фото, дата") == true || node.contentDescription?.startsWith("Photo taken") == true) }
            if (android.os.Build.VERSION.SDK_INT < 33) check(photo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) else {
                val photoBounds = android.graphics.Rect(); photo.getBoundsInScreen(photoBounds)
                instrumentation.uiAutomation.executeShellCommand("input tap ${photoBounds.centerX()} ${photoBounds.centerY()}").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
            // Cloud picker variants expose a single-photo confirmation or return immediately.
            instrumentation.uiAutomation.waitForIdle(500, 3000)
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true || instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("photopicker") == true) {
                clickSystem("Add", "Добавить", "Done", "Готово", "Выбрать", "Select")
            }
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("PNG", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
        } } finally { context.contentResolver.delete(uri, null, null) }
    }
    @Test fun cameraPermissionAndVirtualCapture() {
        configure()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChat(); attach(context.getString(dev.dshmobile.app.R.string.mobile_attach_camera))
            clickSystem("While using the app", "При использовании", "Only this time", "Только в этот раз")
            clickSystem("Shutter", "Capture", "Take photo", "Снять", "Затвор")
            clickSystem("Done", "OK", "Готово", "Save")
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("JPG", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
        }
    }
    @Test fun shareChooserAndSaveMediaStore() {
        configure()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChat()
            compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("media_synthetic-image"))
            compose.waitUntil(15000) { compose.onAllNodesWithTag("media_synthetic-image").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo) } }
            compose.onNodeWithTag("media_synthetic-image").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("image_viewer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("image_viewer").performTouchInput { click() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("viewer_save").fetchSemanticsNodes().isNotEmpty() }
            fun savedImages() = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("synthetic%"), null)?.use { it.count } ?: 0
            val beforeSave = savedImages()
            compose.onNodeWithTag("viewer_save").performClick()
            compose.waitUntil(10000) { savedImages() > beforeSave }
            compose.onNodeWithTag("viewer_share").performClick()
            awaitNode { it.viewIdResourceName in listOf("com.android.intentresolver:id/headline", "android:id/chooser_header") || it.text?.toString()?.contains("Sharing", true) == true }
            assertTrue(File(context.cacheDir, "shared-media").listFiles().orEmpty().any { it.name.startsWith("share-") })
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
    @Test fun fileDownloadSavesDownloadsAndOpensSystem() {
        configure()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChat()
            compose.onNodeWithTag("chat_timeline").performScrollToNode(hasTestTag("file_open_synthetic-file"))
            fun downloadedFiles() = context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("synthetic%"), null)?.use { it.count } ?: 0
            val beforeDownload = downloadedFiles()
            compose.onNodeWithTag("file_open_synthetic-file").performScrollTo().performClick()
            compose.waitUntil(15000) { downloadedFiles() > beforeDownload }
            awaitNode { it.packageName?.toString() != context.packageName && (it.viewIdResourceName?.contains("resolver") == true || it.text?.toString()?.contains("Synthetic") == true || it.text?.toString()?.contains("Отправ") == true) }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
    @Test fun documentsPickerUploadsAndSends() {
        configure()
        val name = "phone08-synthetic-${System.currentTimeMillis()}.txt"
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write("Synthetic file only".toByteArray()) }
        try { ActivityScenario.launch(MainActivity::class.java).use {
            openChat(); attach(context.getString(dev.dshmobile.app.R.string.mobile_attach_files))
            if (android.os.Build.VERSION.SDK_INT < 33) {
                clickSystem("Show roots", "Показать корни", "Открыть список")
                clickSystem("Downloads", "Загрузки")
            }
            val document = awaitNode { it.isVisibleToUser && it.text?.toString() == name }
            var row: AccessibilityNodeInfo? = document
            while (row != null && row.viewIdResourceName?.endsWith(":id/item_root") != true) row = row.parent
            check(requireNotNull(row).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            // DocumentsUI multi-select requires an explicit confirmation after selecting the row.
            instrumentation.uiAutomation.waitForIdle(500, 3000)
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true || instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("photopicker") == true) {
                clickSystem("Open", "Открыть", "Select", "Выбрать")
            }
            compose.waitUntil(15000) { try { compose.onAllNodesWithContentDescription("TXT", substring = true).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false } }
            compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("TXT", substring = true).fetchSemanticsNodes().isEmpty() }
        } } finally { context.contentResolver.delete(uri, null, null) }
    }
}
