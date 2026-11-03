package dev.dshmobile.app.acceptance

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.ui.chat.HeifUpload
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real platform decoder; opt-in sample remains private and is never bundled with the app. */
class HeifConversionStandTest {
    @Test fun convertsRealHeifAndReleasesCorruptSourceWithoutLeavingOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "upload-sample.heic")
        org.junit.Assume.assumeTrue("Private isolated HEIC sample required", source.isFile)
        val (uri, bytes) = HeifUpload.convert(context, Uri.fromFile(source))
        val output = File(context.cacheDir, "shared-media/${uri.lastPathSegment}")
        try {
            assertTrue(bytes in 1..20L * 1024 * 1024)
            assertEquals(bytes, output.length())
            val bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
            try { assertEquals(1600, bitmap.width); assertEquals(1200, bitmap.height) } finally { bitmap.recycle() }
            val invalid = File(context.filesDir, "upload-invalid.heic").apply { writeText("not HEIC\uFEFF\r\n😀") }
            val before = output.parentFile!!.list()!!.toSet()
            try {
                assertThrows(java.io.IOException::class.java) { HeifUpload.convert(context, Uri.fromFile(invalid)) }
                assertEquals(before, output.parentFile!!.list()!!.toSet())
            } finally { invalid.delete() }
        } finally { output.delete() }
    }
}
