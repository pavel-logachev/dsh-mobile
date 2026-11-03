package dev.dshmobile.app.acceptance

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in, isolated loopback host and real Android photo picker. No phone/DSH access. */
class UploadImageStandTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (predicate(n)) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        var result: AccessibilityNodeInfo? = null
        compose.waitUntil(15_000) { result = find(instrumentation.uiAutomation.rootInActiveWindow); result != null }
        return requireNotNull(result)
    }
    private fun tap(n: AccessibilityNodeInfo) {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = android.graphics.Rect(); n.getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(context.filesDir, "upload-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun pickedImagesUploadAndSend() {
        val configFile = File(context.filesDir, "upload-stand.json")
        org.junit.Assume.assumeTrue("Private isolated upload stand config required", configFile.isFile)
        val config = JSONObject(configFile.readText())
        val endpoint = if (config.has("endpoint")) kotlinx.serialization.json.Json.decodeFromString<HostEndpoint>(config.getJSONObject("endpoint").toString()) else HostEndpoint("http://127.0.0.1:${config.getInt("port")}")
        runBlocking { EncryptedStateStore(context).write(StoredState(host = PairedHost(endpoint, config.getString("deviceId"), config.getString("deviceToken"), "Synthetic upload stand"))) }
        val format = InstrumentationRegistry.getArguments().getString("uploadFormat") ?: "png"
        val jpeg = format == "jpeg"
        val heic = format == "heic"
        val name = "upload-stand-${System.currentTimeMillis()}.${if (heic) "heic" else if (jpeg) "jpg" else "png"}"
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, if (heic) "image/heic" else if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() + 60_000)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000 + 60)
        })!!
        if (heic) {
            File(context.filesDir, "upload-sample.heic").inputStream().use { source -> context.contentResolver.openOutputStream(uri)!!.use { source.copyTo(it) } }
        } else {
            val width = if (jpeg) 2400 else 96
            val height = if (jpeg) 1800 else 64
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val random = java.util.Random(73)
            val row = IntArray(width)
            for (y in 0 until height) { for (x in row.indices) row[x] = random.nextInt() or (0xff shl 24); bitmap.setPixels(row, 0, width, 0, y, width, 1) }
            context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 98, it)) }; bitmap.recycle()
        }
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.length }
        if (jpeg) assertTrue("JPEG must be several MB: $size", size > 3 * 1024 * 1024)
        File(context.filesDir, "upload-source-$format.json").writeText(JSONObject().put("name", name).put("size", size).toString())
        try { ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("session_chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("session_chat").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("message_input").performTextReplacement("Synthetic picked $format upload")
            compose.onNodeWithContentDescription(context.getString(R.string.mobile_attach)).performClick()
            compose.onNodeWithText(context.getString(R.string.mobile_attach_photos)).performClick()
            capture("picker-$format")
            val photo = node { n -> n.isVisibleToUser && (n.contentDescription?.startsWith("Photo taken") == true || n.contentDescription?.startsWith("Фото, дата") == true || n.text?.toString() == name) }
            tap(photo)
            instrumentation.uiAutomation.waitForIdle(500, 3000)
            val pkg = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (pkg.contains("photopicker") || pkg.contains("documentsui")) {
                // Picker may finish between package inspection and confirmation lookup.
                val next = node { n -> n.isVisibleToUser && (n.packageName?.toString() == context.packageName || listOf("Add", "Добавить", "Done", "Готово", "Select", "Выбрать").any { label -> n.text?.toString()?.startsWith(label) == true }) }
                if (next.packageName?.toString() != context.packageName) tap(next)
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("send_message").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(context.getString(R.string.mobile_attach_fix_errors)).fetchSemanticsNodes().isNotEmpty() ||
                    runBlocking { EncryptedStateStore(context).read() }.attachmentDrafts["chat"]?.any { it.uploadId != null } == true
            }
            capture("picked-$format")
            val draft = runBlocking { EncryptedStateStore(context).read() }.attachmentDrafts["chat"]?.singleOrNull()
            File(context.filesDir, "upload-picked-$format.json").writeText(JSONObject().put("pickerMime", context.contentResolver.getType(uri)).put("draftMime", draft?.mime).put("draftSize", draft?.size).put("hasReceipt", draft?.uploadId != null).toString())
            if (heic) assertEquals("HEIC must reach the agent as an image", "image/jpeg", draft?.mime)
            if (InstrumentationRegistry.getArguments().getString("uploadFailureCapture") == "true") {
                // Explicit bounded operator window for adb exec-out screencap of the pre-fix failure.
                instrumentation.uiAutomation.executeShellCommand("log -t UploadStand failure_capture_ready").close()
                Thread.sleep(30_000)
            }
            compose.onNodeWithText(context.getString(R.string.mobile_attach_fix_errors)).assertDoesNotExist()
            compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().any { it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty() } }
            // Empty editor alone proves only local admission. Wait for authoritative image history.
            val media = SemanticsMatcher("authoritative attachment") { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("media_image-") == true }
            compose.waitUntil(30_000) { compose.onAllNodes(media).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat_timeline").performScrollToNode(hasText("Synthetic picked $format upload", substring = true))
            compose.onNodeWithText("Synthetic picked $format upload", substring = true).assertIsDisplayed()
            capture("sent-$format")
        } } finally { context.contentResolver.delete(uri, null, null) }
    }
}
