package dev.dshmobile.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val queuedTasks = AtomicInteger()
        val ordinaryFinished = CountDownLatch(1)
        lateinit var proxy: RelayLoopbackProxy
        proxy = RelayLoopbackProxy(endpoint(9), owner) { owner.close { proxy.closePhysical() } }
        val listenerAddress = proxy.proxy.address() as InetSocketAddress
        val callers = Executors.newFixedThreadPool(2)
        try {
            assertTrue(owner.submit {
                entered.countDown()
                try { release.await() } finally { ordinaryFinished.countDown() }
            })
            assertTrue("Ordinary IO started", entered.await(2, TimeUnit.SECONDS))
            repeat(8) { assertTrue(owner.submit { queuedTasks.incrementAndGet() }) }
            assertFalse("All eight ordinary slots are occupied", owner.submit { queuedTasks.incrementAndGet() })
            val ready = CountDownLatch(2)
            val begin = CountDownLatch(1)
            val first = callers.submit { ready.countDown(); begin.await(); proxy.retire() }
            val second = callers.submit { ready.countDown(); begin.await(); proxy.close() }
            assertTrue("Both retirement callers are ready", ready.await(2, TimeUnit.SECONDS))
            begin.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
            assertEquals("Retirement did not release or interrupt ordinary IO", 1L, ordinaryFinished.count)
            owner.close { proxy.closePhysical() }
            assertFalse("Terminal retirement rejects new ordinary work", owner.submit { queuedTasks.incrementAndGet() })
            release.countDown()
            owner.awaitClosed()
            assertEquals("Terminal close discarded all queued cancellation tasks", 0, queuedTasks.get())
            awaitConnectionRefused(listenerAddress)
        } finally {
            proxy.retire()
            owner.close { proxy.closePhysical() }
            release.countDown()
            callers.shutdownNow()
            owner.awaitClosed()
        }
    }

    private suspend fun awaitConnectionRefused(address: InetSocketAddress) = withTimeout(2_000) {
        // JDK close can return before a blocked Linux accept releases its native listener.
        // A final TCP handshake may still succeed; await actual refusal, not an IO timeout.
        while (true) {
            try { Socket().use { it.connect(address, 500) } }
            catch (_: ConnectException) { return@withTimeout }
            yield()
        }
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
