package dev.dshmobile.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real AndroidKeyStore + AtomicFile, on an isolated test app only; never a live paired device. */
@RunWith(AndroidJUnit4::class)
class EncryptedStateStoreInstrumentedTest {
    @Test fun bootstrapTlsCloseAndReplacementOnMainThread() = runBlocking {
        var checkpoint = "tls.setup"
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        var bootstrap: HostApi? = null
        var replacement: HostApi? = null
        try {
            server.useHttps(certificates.sslSocketFactory(), false)
            server.start()
            val endpoint = HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"),
                okhttp3.CertificatePinner.pin(certificate.certificate), certificate.certificatePem())
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setResponseCode(201)
                .setBody("""{"deviceId":"synthetic-device-id","deviceToken":"synthetic-only-device-bearer","hostName":"Synthetic host","protocolVersion":1}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"protocolVersion":1,"hostName":"Synthetic host","upstreamVersion":"fixture","capabilities":{"sessions":false,"textPrompt":false,"cancel":false,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}"""))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                checkpoint = "tls.bootstrap-create"
                bootstrap = HostApi(endpoint)
                checkpoint = "tls.bootstrap-pair"
                val paired = bootstrap!!.pair("synthetic-only-pairing-token", "Synthetic phone")
                checkpoint = "tls.bootstrap-close-main"
                bootstrap!!.close()
                checkpoint = "tls.bootstrap-await-main"
                bootstrap!!.awaitClosed()
                checkpoint = "tls.closed-reuse-rejected"
                try { bootstrap!!.capabilities(); throw AssertionError("Closed transport admitted a request") }
                catch (expected: MobileFailure) { assertEquals("network_unavailable", expected.key) }
                checkpoint = "tls.device-create-main"
                replacement = HostApi(endpoint, paired.deviceToken)
                checkpoint = "tls.device-request"
                assertEquals("Synthetic host", replacement!!.capabilities().hostName)
                checkpoint = "tls.device-close-main"
                replacement!!.close()
                checkpoint = "tls.device-await-main"
                replacement!!.awaitClosed()
            }
        } catch (failure: Exception) {
            throw AssertionError("Android TLS lifecycle failed at $checkpoint [${failure.javaClass.name}]; details redacted")
        } finally {
            runCatching { bootstrap?.close() }
            runCatching { replacement?.close() }
            server.shutdown()
        }
    }

    @Test fun activeSseAndHttpCancellationAreOffMainThread() = runBlocking {
        var checkpoint = "active.setup"
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        var api: HostApi? = null
        var primaryFailure: AssertionError? = null
        try {
            server.useHttps(certificates.sslSocketFactory(), false)
            server.protocols = listOf(okhttp3.Protocol.HTTP_1_1) // Native Node host uses H1; NO_RESPONSE observes TCP EOF.
            server.start()
            val endpoint = HostEndpoint(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"),
                okhttp3.CertificatePinner.pin(certificate.certificate), certificate.certificatePem())
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: snapshot\ndata: {\"session\":{\"id\":\"synthetic-session\",\"title\":\"Synthetic\",\"workspaceId\":\"synthetic-workspace\",\"updatedAt\":1,\"running\":false,\"canExecute\":false},\"messages\":[],\"cursor\":1,\"hasMore\":false,\"activity\":\"idle\"}\n\n" + ": synthetic keepalive\n\n".repeat(4096))
                .throttleBody(400, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
            val snapshot = kotlinx.coroutines.CompletableDeferred<Unit>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                checkpoint = "active.sse-create"
                api = HostApi(endpoint, "synthetic-only-device-bearer")
                val source = api!!.observe("synthetic-session", { snapshot.complete(Unit) }, { snapshot.completeExceptionally(it) })
                kotlinx.coroutines.withTimeout(5000) { snapshot.await() }
                checkpoint = "active.sse-cancel-main"
                source.cancel()
                server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                checkpoint = "active.call-create"
                val call = async { api!!.capabilities() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    check(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)?.path == "/v1/sessions/synthetic-session/events") { "Synthetic SSE request missing" }
                    check(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)?.path == "/v1/capabilities") { "Synthetic active request missing" }
                }
                checkpoint = "active.call-cancel-main"
                call.cancel()
                call.join()
                check(call.isCancelled) { "Active request did not retain cancellation" }
                checkpoint = "active.close-main"
                api!!.close()
                checkpoint = "active.await-main"
                api!!.awaitClosed()
                checkpoint = "active.complete"
            }
        } catch (failure: Throwable) {
            primaryFailure = AssertionError("Active Android cancellation failed at $checkpoint [${failure.javaClass.name}]; details redacted")
            throw primaryFailure!!
        } finally {
            runCatching { api?.close() }
            try { server.shutdown() }
            catch (cleanup: Throwable) {
                val redacted = AssertionError("Active fixture shutdown failed after $checkpoint [${cleanup.javaClass.name}]; details redacted")
                if (primaryFailure != null) primaryFailure!!.addSuppressed(redacted) else throw redacted
            }
        }
    }

    @Test fun encryptedV1AndRelayV2RoundTripAndClear() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = File(context.noBackupFilesDir, "mobile-state.enc")
        check(!record.exists()) { "Requires an isolated application with no paired state" }
        var checkpoint = "setup"
        var failureClass = "none"
        val store = EncryptedStateStore(context)
        try {
            checkpoint = "read.empty"
            assertNull(store.read().host)
            val base = HostEndpoint("https://synthetic-host.invalid", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            val direct = StoredState(host = PairedHost(base, "synthetic-device-id", "synthetic-only-device-bearer", "Synthetic host"))
            checkpoint = "v1.write"
            store.write(direct)
            checkpoint = "v1.file"
            assertTrue(record.isFile)
            val firstCiphertext = record.readBytes()
            assertEquals(1, firstCiphertext[0].toInt())
            assertFalse(firstCiphertext.toString(Charsets.UTF_8).contains("synthetic-only-device-bearer"))
            checkpoint = "v1.read-new-store"
            val v1 = EncryptedStateStore(context).read()
            assertEquals(base, v1.host!!.endpoint)
            assertEquals("synthetic-only-device-bearer", v1.host!!.deviceToken)
            val relay = RelaySettings("wss://relay.example/synthetic", "0123456789abcdef0123456789abcdef", "f172cf3d-40e0-43e6-93c4-b27bdb3b8877",
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", System.currentTimeMillis() + 365L * 24 * 60 * 60_000)
            val relayedEndpoint = base.copy(baseUrl = "https://h-${relay.routeId}.dsh.invalid", certificatePem = "synthetic certificate fixture", relay = relay)
            val remote = StoredState(host = PairedHost(relayedEndpoint, "synthetic-device-id", "synthetic-only-device-bearer", "Synthetic host"),
                selectedSessionId = "synthetic-session", drafts = mapOf("synthetic-session" to "synthetic durable draft"),
                pending = StoredCommand("f072cf3d-40e0-43e6-93c4-b27bdb3b8877", "send", "synthetic-session", "synthetic pending message", status = "uncertain"))
            checkpoint = "v2.write"
            store.write(remote)
            checkpoint = "v2.read-new-store"
            val v2 = EncryptedStateStore(context).read()
            assertEquals(relayedEndpoint, v2.host!!.endpoint)
            assertEquals(relay.accessToken, v2.host!!.endpoint.relay!!.accessToken)
            assertEquals(remote.pending, v2.pending)
            assertEquals(remote.drafts, v2.drafts)
            checkpoint = "v2.fresh-nonce"
            val ciphertext = record.readBytes()
            store.write(remote)
            assertFalse(ciphertext.contentEquals(record.readBytes()))
            checkpoint = "clear"
            store.clear()
            assertFalse(record.exists())
            assertNull(EncryptedStateStore(context).read().host)
            checkpoint = "rekey.write-read-clear"
            val rekeyed = EncryptedStateStore(context)
            rekeyed.write(remote)
            assertNotNull(rekeyed.read().host)
            rekeyed.clear()
            assertFalse(record.exists())
        } catch (failure: Exception) {
            failureClass = failure.javaClass.name
            // Runner sees only a static phase and exception class, never messages, causes or state.
            throw AssertionError("Encrypted persistence failed at $checkpoint [$failureClass]; details redacted")
        } finally {
            // Do not obscure the first class-only evidence with a second clear failure.
            runCatching { store.clear() }
        }
    }
}
