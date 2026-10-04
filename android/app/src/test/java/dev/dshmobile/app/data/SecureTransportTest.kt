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
    @Test fun `release invitation with self-signed IP SAN allows HTTPS without cleartext exceptions`() {
        val address = "127.0.0.1" // IP SAN over a disposable loopback server; no real LAN address/route.
        val cert = HeldCertificate.Builder().commonName("synthetic IP host").addSubjectAlternativeName(address).build()
        assertEquals(7, cert.certificate.subjectAlternativeNames!!.single()[0])
        withServer(cert) { server ->
            val endpoint = releaseInvitation(cert, server, address)
            val client = SecureTransport.client(endpoint)
            try {
                server.enqueue(MockResponse().setBody("synthetic IP HTTPS"))
                client.newCall(Request.Builder().url(endpoint.baseUrl + "/v1/capabilities").build()).execute().use {
                    assertEquals("synthetic IP HTTPS", it.body!!.string())
                }
                val received = server.takeRequest()
                assertEquals("$address:${server.port}", received.getHeader("Host") ?: received.getHeader(":authority"))
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `release invitation with self-signed DNS SAN allows a tailnet-shaped hostname`() {
        val hostname = "pc.synthetic-tailnet.example" // Reserved example domain, never an actual tailnet.
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(hostname).build()
        withServer(cert) { server ->
            val endpoint = releaseInvitation(cert, server, hostname)
            val client = SecureTransport.client(endpoint).newBuilder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }).build()
            try {
                server.enqueue(MockResponse().setBody("synthetic DNS HTTPS"))
                client.newCall(Request.Builder().url(endpoint.baseUrl).build()).execute().use {
                    assertEquals("synthetic DNS HTTPS", it.body!!.string())
                }
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `IP URL never falls back to DNS SAN or common name and still checks its pin`() {
        val address = "127.0.0.1"
        for (certificate in listOf(
            HeldCertificate.Builder().commonName(address).addSubjectAlternativeName("localhost").build(),
            HeldCertificate.Builder().commonName(address).addSubjectAlternativeName("192.0.2.11").build(),
            HeldCertificate.Builder().addSubjectAlternativeName(address).build(),
        )) {
            withServer(certificate) { server ->
                val endpoint = releaseInvitation(certificate, server, address).let { value ->
                    if (certificate.certificate.subjectAlternativeNames!!.single()[1] == address)
                        value.copy(pinSha256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") else value
                }
                val client = SecureTransport.client(endpoint)
                try {
                    assertThrows(IOException::class.java) {
                        client.newCall(Request.Builder().url(endpoint.baseUrl).header("Authorization", "Bearer synthetic-never-send").build()).execute().close()
                    }
                    assertEquals(0, server.requestCount)
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }

    private fun releaseInvitation(cert: HeldCertificate, server: MockWebServer, host: String): HostEndpoint {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(1))
            put("baseUrl", kotlinx.serialization.json.JsonPrimitive("https://$host:${server.port}"))
            put("pairingToken", kotlinx.serialization.json.JsonPrimitive("synthetic-one-use-token"))
            put("pinSha256", kotlinx.serialization.json.JsonPrimitive(CertificatePinner.pin(cert.certificate)))
            put("certificatePem", kotlinx.serialization.json.JsonPrimitive(cert.certificatePem()))
        }.toString()
        return Invitation.parse(json, debug = false).endpoint
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
