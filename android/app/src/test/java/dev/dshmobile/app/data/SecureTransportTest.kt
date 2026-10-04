package dev.dshmobile.app.data

import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SecureTransportTest {
    @Test fun `trusted invited certificate plus hostname and pin allows real HTTPS`() {
        val cert = HeldCertificate.Builder().commonName("synthetic").addSubjectAlternativeName("localhost").build()
        withServer(cert) { server ->
            val endpoint = HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), CertificatePinner.pin(cert.certificate), cert.certificatePem())
            val client = SecureTransport.client(endpoint)
            try {
                server.enqueue(MockResponse().setBody("synthetic"))
                client.newCall(Request.Builder().url(server.url("/").newBuilder().host("localhost").build()).build()).execute().use { assertEquals(200, it.code) }
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun `correct invitation trust does not bypass wrong pin`() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        withServer(cert) { server ->
            val endpoint = HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", cert.certificatePem())
            rejects(endpoint, server)
        }
    }
    @Test fun `correct pin and invited trust does not bypass hostname mismatch`() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("not-localhost.invalid").build()
        withServer(cert) { server ->
            rejects(HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), CertificatePinner.pin(cert.certificate), cert.certificatePem()), server)
        }
    }
    @Test fun `pin alone does not trust a self-signed certificate`() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        withServer(cert) { server ->
            rejects(HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), CertificatePinner.pin(cert.certificate)), server)
        }
    }
    @Test fun `expired or future invitation anchor is rejected before credential transmission`() {
        val now = System.currentTimeMillis()
        for (range in listOf((now - 20_000) to (now - 10_000), (now + 10_000) to (now + 20_000))) {
            val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").validityInterval(range.first, range.second).build()
            assertEquals("certificate_invalid", assertThrows(MobileFailure::class.java) { SecureTransport.certificate(cert.certificatePem()) }.key)
        }
    }
    @Test fun `expired server leaf remains rejected under valid invitation CA`() {
        val ca = HeldCertificate.Builder().certificateAuthority(0).build()
        val now = System.currentTimeMillis()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(ca).validityInterval(now - 20_000, now - 10_000).build()
        withServer(leaf, ca) { server ->
            rejects(HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), CertificatePinner.pin(leaf.certificate), ca.certificatePem()), server)
        }
    }
    private fun rejects(endpoint: HostEndpoint, server: MockWebServer) {
        val client = SecureTransport.client(endpoint)
        try { assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(server.url("/").newBuilder().host("localhost").build()).build()).execute().close() } }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private fun withServer(cert: HeldCertificate, ca: HeldCertificate? = null, block: (MockWebServer) -> Unit) {
        val tls = HandshakeCertificates.Builder().apply {
            if (ca != null) heldCertificate(cert, ca.certificate) else heldCertificate(cert)
        }.build()
        MockWebServer().use { server -> server.useHttps(tls.sslSocketFactory(), false); server.start(); block(server) }
    }
}
