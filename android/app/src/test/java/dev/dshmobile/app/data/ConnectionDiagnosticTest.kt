package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticTest {
    @Test fun `release diagnostic never emits arbitrary text or exception messages`() {
        val secrets = listOf("https://user:password@example.test/path?token=secret", "host.example.test", "Bearer private-token", "\uFEFFФото😀\r\n\uFEFF")
        for (secret in secrets) {
            val line = ConnectionDiagnostic.line(secret, secret, object : IllegalStateException(secret) {}, -1, -1, false, ConnectionNetwork())
            assertEquals("stage=unknown code=unknown exception=other attempt=0 elapsedMs=0 foreground=false validated=unknown vpn=unknown blocked=unknown", line)
            assertFalse(line.contains(secret))
            assertFalse(ConnectionDiagnostic.line("connect", "fail", java.io.IOException(secret), 1, 9, true, ConnectionNetwork(true, true, false)).contains(secret))
            assertEquals("unknown", ConnectionDiagnostic.code(MobileFailure(secret)))
        }
    }
    @Test fun `rate is bounded and replenishes without retaining dropped events`() {
        var clock = 0L
        val lines = mutableListOf<String>()
        val diagnostic = ConnectionDiagnostic(clock = { clock }, sink = { lines.add(it) })
        repeat(500) { diagnostic.record("network-callback") }
        assertEquals(120, lines.size)
        clock = 60_000
        diagnostic.record("manual-refresh")
        assertEquals(121, lines.size)
        assertTrue(lines.last().contains("stage=manual-refresh"))
    }
    @Test fun `known exception categories and network flags remain useful`() {
        assertEquals("timeout", ConnectionDiagnostic.code(java.net.SocketTimeoutException("secret")))
        assertEquals("tls", ConnectionDiagnostic.code(javax.net.ssl.SSLException("secret")))
        assertEquals("stage=sse-fail code=timeout exception=SocketTimeoutException attempt=2 elapsedMs=120 foreground=true validated=true vpn=true blocked=false",
            ConnectionDiagnostic.line("sse-fail", "timeout", java.net.SocketTimeoutException("secret"), 2, 120, true, ConnectionNetwork(true, true, false)))
    }
}
