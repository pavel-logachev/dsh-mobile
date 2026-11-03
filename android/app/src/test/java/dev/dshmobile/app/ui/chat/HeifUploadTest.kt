package dev.dshmobile.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class HeifUploadTest {
    @Test fun `HEIF MIME and neutral provider extension are converted but known other types are not`() {
        for (mime in listOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence", "IMAGE/HEIC")) assertTrue(HeifUpload.matches(mime, "photo"))
        for (name in listOf("photo.heic", "Фото 😀.HEIF", "0.heic")) {
            assertTrue(HeifUpload.matches(null, name))
            assertTrue(HeifUpload.matches("application/octet-stream", name))
        }
        for (name in listOf("", "heic", "photo.heic.txt", "photo.heic\n", "photo.heic\uFEFF", "photo.ｈｅｉｃ")) assertFalse(HeifUpload.matches(null, name))
        for (mime in listOf("image/jpeg", "image/png", "application/pdf", "text/plain")) assertFalse(HeifUpload.matches(mime, "photo.heic"))
    }
    @Test fun `converted names remain bounded and use JPEG extension`() {
        assertEquals("Photo 1.jpg", attachmentDisplayName("123.jpg", "image/jpeg", "Photo 1"))
        val name = attachmentDisplayName("Фото 😀".repeat(100) + ".jpg", "image/jpeg", "Photo 1")
        assertTrue(name.endsWith(".jpg")); assertTrue(name.toByteArray().size <= 256)
    }
}
