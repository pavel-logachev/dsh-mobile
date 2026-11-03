package dev.dshmobile.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** HEIF is not a model image MIME. Decode locally, retaining only a bounded JPEG draft. */
internal object HeifUpload {
    fun matches(mime: String?, name: String): Boolean = mime?.lowercase() in setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence") ||
        (mime == null || mime == "application/octet-stream") && name.substringAfterLast('.', "").lowercase() in setOf("heic", "heif")

    fun convert(context: Context, uri: Uri): Pair<Uri, Long> {
        // ImageDecoder applies HEIF container transforms. Do not rotate again from EXIF.
        if (Build.VERSION.SDK_INT < 28) throw java.io.IOException("image_unavailable")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(ImageSizing.sample(info.size.width, info.size.height, 4096))
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            if (bitmap.allocationByteCount > ImageSizing.MAX_BITMAP_BYTES) throw java.io.IOException("image_memory")
            val file = File.createTempFile("camera-heif-", ".jpg", TemporaryMedia.directory(context))
            try {
                file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw java.io.IOException("image_unavailable") }
                return FileProvider.getUriForFile(context, "${context.packageName}.media", file) to file.length()
            } catch (failure: Throwable) { TemporaryMedia.delete(context, file); throw failure }
        } finally { bitmap.recycle() }
    }
}
