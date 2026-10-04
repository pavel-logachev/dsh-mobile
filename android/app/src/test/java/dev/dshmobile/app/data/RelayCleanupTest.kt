package dev.dshmobile.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayCleanupTest {
    private val host = "h-0123456789abcdef0123456789abcdef.dsh.invalid"
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
    private fun endpoint(port: Int) = HostEndpoint("https://$host", CertificatePinner.pin(certificate.certificate), certificate.certificatePem(),
        RelaySettings("ws://127.0.0.1:$port", "0123456789abcdef0123456789abcdef", "a172cf3d-40e0-43e6-93c4-b27bdb3b8877",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", System.currentTimeMillis() + 600_000))

    @Test fun `full cleanup queue and concurrent retirement never wait for blocked ordinary IO`(): Unit = runBlocking {
        val owner = TransportIoOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(owner.submit { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        repeat(8) { assertTrue(owner.submit { fail("Terminal close must discard queued cancellation tasks") }) }
        lateinit var proxy: RelayLoopbackProxy
        proxy = RelayLoopbackProxy(endpoint(9), owner) { owner.close { proxy.closePhysical() } }
        val listenerAddress = proxy.proxy.address() as java.net.InetSocketAddress
        val callers = Executors.newFixedThreadPool(2)
        try {
            val begin = CountDownLatch(1)
            val first = callers.submit { begin.await(); proxy.retire() }
            val second = callers.submit { begin.await(); proxy.close() }
            begin.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
            owner.close { proxy.closePhysical() }
            release.countDown()
            owner.awaitClosed()
            assertThrows(java.io.IOException::class.java) {
                Socket().use { it.connect(listenerAddress, 500) }
            }
        } finally { release.countDown(); proxy.retire(); proxy.closePhysical(); callers.shutdownNow() }
    }
    @Test fun `closing during pending outer upgrade aborts actual TCP and prevents later startup`() = runBlocking {
        repeat(12) {
            ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).use { relay ->
                val accepted = CountDownLatch(1)
                val remoteEnded = CountDownLatch(1)
                val serverWorker = Thread {
                    relay.accept().use { socket ->
                        accepted.countDown()
                        socket.soTimeout = 3000
                        try { while (socket.getInputStream().read() >= 0) { } }
                        catch (_: java.io.IOException) { }
                        finally { remoteEnded.countDown() }
                    }
                }.apply { isDaemon = true; start() }
                val endpoint = endpoint(relay.localPort)
                val proxy = RelayLoopbackProxy(endpoint)
                val client = SecureTransport.client(endpoint, proxy)
                val callWorker = Executors.newSingleThreadExecutor()
                try {
                    val call = callWorker.submit {
                        try { client.newCall(Request.Builder().url(endpoint.baseUrl).build()).execute().close() }
                        catch (_: java.io.IOException) { }
                    }
                    assertTrue(accepted.await(2, TimeUnit.SECONDS))
                    proxy.close()
                    proxy.awaitClosed()
                    assertTrue("Outer raw TCP is aborted, not merely engine-closed", remoteEnded.await(2, TimeUnit.SECONDS))
                    call.get(2, TimeUnit.SECONDS)
                    serverWorker.join(1000)
                    assertFalse(serverWorker.isAlive)
                } finally {
                    proxy.close(); proxy.awaitClosed()
                    client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                    callWorker.shutdownNow()
                }
            }
        }
    }
}
