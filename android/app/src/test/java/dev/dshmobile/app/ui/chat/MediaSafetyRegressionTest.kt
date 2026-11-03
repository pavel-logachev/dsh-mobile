package dev.dshmobile.app.ui.chat

import android.content.Context
import dev.dshmobile.app.model.DraftAttachment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaSafetyRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `old camera source in saved draft remains readable but abandoned source is cleaned`() {
        val file = File(TemporaryMedia.directory(context), "camera-saved.jpg").apply {
            writeText("photo"); setLastModified(System.currentTimeMillis() - TemporaryMedia.CAMERA_TTL_MS - 1000)
        }
        val uri = android.net.Uri.parse("content://${context.packageName}.media/shared_media/${file.name}")
        val draft = DraftAttachment("saved", uri.toString(), "photo.jpg", 5, "image/jpeg", temporary = true)
        TemporaryMedia.cleanup(context, listOf(draft))
        TemporaryMedia.requireReadable(file)
        assertEquals("photo", file.readText())
        TemporaryMedia.cleanup(context, emptyList())
        assertFalse(file.exists())
    }

    @Test fun `converted HEIF uses the same draft aware source lifetime`() {
        val file = File(TemporaryMedia.directory(context), "camera-heif-converted.jpg").apply {
            writeText("jpeg"); setLastModified(System.currentTimeMillis() - TemporaryMedia.CAMERA_TTL_MS - 1000)
        }
        val uri = android.net.Uri.parse("content://${context.packageName}.media/shared_media/${file.name}")
        val draft = DraftAttachment("heif", uri.toString(), "photo.jpg", 4, "image/jpeg", temporary = true)
        TemporaryMedia.cleanup(context, listOf(draft)); TemporaryMedia.requireReadable(file)
        assertTrue(file.exists())
        TemporaryMedia.cleanup(context, emptyList()); assertFalse(file.exists())
    }

    @Test fun `expired share is refused even before cleanup`() {
        val file = File(TemporaryMedia.directory(context), "share-expired.jpg").apply {
            writeText("export"); setLastModified(System.currentTimeMillis() - TemporaryMedia.SHARE_TTL_MS - 1000)
        }
        assertThrows(java.io.FileNotFoundException::class.java) { TemporaryMedia.requireReadable(file) }
    }

    @Test fun `all EXIF rotations and reflections map image axes correctly`() {
        // Independent EXIF specification examples: transformed (1,2), before
        // Bitmap.createBitmap normalizes the translated bounding rectangle.
        val expected = listOf(1f to 2f, -1f to 2f, -1f to -2f, 1f to -2f,
            2f to 1f, -2f to 1f, -2f to -1f, 2f to -1f)
        expected.forEachIndexed { index, point ->
            val actual = floatArrayOf(1f, 2f)
            exifTransform(index + 1).mapPoints(actual)
            assertEquals("orientation ${index + 1} x", point.first, actual[0], .001f)
            assertEquals("orientation ${index + 1} y", point.second, actual[1], .001f)
        }
    }
}
