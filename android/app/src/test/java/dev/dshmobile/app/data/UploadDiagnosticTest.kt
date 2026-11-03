package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test

class UploadDiagnosticTest {
    @Test fun `diagnostic is allowlisted and rejects arbitrary server and exception text`() {
        val secret = "https://user:secret@example.test/private?token=secret\r\nФото😀"
        assertEquals("stage=unknown code=unknown transport=unknown http=0 sent=0 size=0 elapsedMs=0",
            UploadDiagnostic.line(secret, secret, status = -1, sent = -1, size = -1, elapsedMs = -1))
        assertEquals("unknown", UploadDiagnostic.code(IllegalStateException(secret)))
        assertEquals("unknown", UploadDiagnostic.code(MobileFailure(secret)))
        assertEquals("io", UploadDiagnostic.code(java.io.IOException(secret)))
        assertEquals("source_denied", UploadDiagnostic.code(SecurityException(secret)))
        assertEquals("source_missing", UploadDiagnostic.code(java.io.FileNotFoundException(secret)))
        assertEquals("timeout", UploadDiagnostic.code(java.net.SocketTimeoutException(secret)))
        assertEquals("tls", UploadDiagnostic.code(javax.net.ssl.SSLException(secret)))
    }
    @Test fun `diagnostic preserves actionable bounded categories in release safe form`() {
        assertEquals("stage=response code=unsupported_media_type transport=relay http=415 sent=32768 size=5257297 elapsedMs=15002",
            UploadDiagnostic.line("response", "unsupported_media_type", true, 415, 32768, 5257297, 15002))
        assertEquals("forbidden", UploadDiagnostic.code(MobileFailure("forbidden")))
        assertTrue(UploadDiagnostic.line("transport", "timeout", false).contains("transport=direct"))
    }
}
