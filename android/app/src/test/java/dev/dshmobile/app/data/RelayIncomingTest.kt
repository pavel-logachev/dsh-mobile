package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayIncomingTest {
    @Test fun `completing a local write during watchdog decision cannot create a false stall`() {
        val input = RelayIncoming { TimeUnit.SECONDS.toNanos(20) }
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextClockRead = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val drain = worker.submit {
            input.drainTo(object : OutputStream() {
                override fun write(byte: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writing.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            })
        }
        try {
            input.offer(byteArrayOf(1))
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertFalse("A just-completed write is not a ten-second stall", input.isStalled {
                // Force finally { writingAt = 0 } between the decision's snapshot and elapsed-time read.
                release.countDown()
                input.finish()
                drain.get(2, TimeUnit.SECONDS)
                nextClockRead.countDown()
                TimeUnit.SECONDS.toNanos(21)
            })
            assertEquals(0L, nextClockRead.count)
        } finally { release.countDown(); input.abort(); worker.shutdownNow() }
    }

    @Test fun `snapshot decision still detects a genuinely stalled local write`() {
        val input = RelayIncoming { TimeUnit.SECONDS.toNanos(20) }
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val drain = worker.submit {
            input.drainTo(object : OutputStream() {
                override fun write(byte: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writing.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            })
        }
        try {
            input.offer(byteArrayOf(1))
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            assertFalse(input.isStalled(TimeUnit.SECONDS.toNanos(29)))
            assertTrue(input.isStalled(TimeUnit.SECONDS.toNanos(30)))
            input.finish()
            release.countDown()
            drain.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); input.abort(); worker.shutdownNow() }
    }

    @Test fun `EOF drains already received bytes after the current local write completes`() {
        val input = RelayIncoming()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val worker = Executors.newSingleThreadExecutor()
        val drain = worker.submit {
            input.drainTo(object : OutputStream() {
                override fun write(byte: Int) { output.write(byte) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writing.countDown()
                    assertTrue("Release the pending local write", release.await(3, TimeUnit.SECONDS))
                    output.write(bytes, offset, length)
                }
            })
        }
        try {
            input.offer("headers:".toByteArray())
            assertTrue("Receiver is paused inside a local write", writing.await(2, TimeUnit.SECONDS))
            input.offer("complete-body".toByteArray())
            input.finish()
            input.offer("late-data".toByteArray()) // No more bytes may be admitted after EOF.
            release.countDown()
            drain.get(2, TimeUnit.SECONDS)
            assertEquals("headers:complete-body", output.toString("UTF-8"))
        } finally { release.countDown(); input.abort(); worker.shutdownNow() }
    }

    @Test fun `full inbound queue backpressures the receiver and resumes without losing accepted bytes`() {
        val input = RelayIncoming()
        val output = ByteArrayOutputStream()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offering = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val first = ByteArray(32 * 1024) { 0 }
        val drain = workers.submit {
            input.drainTo(object : OutputStream() {
                override fun write(byte: Int) { output.write(byte) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (output.size() == 0) {
                        writing.countDown()
                        assertTrue(release.await(3, TimeUnit.SECONDS))
                    }
                    output.write(bytes, offset, length)
                }
            })
        }
        try {
            input.offer(first)
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            repeat(8) { index -> input.offer(ByteArray(32 * 1024) { (index + 1).toByte() }) }
            val last = workers.submit {
                offering.countDown()
                input.offer(ByteArray(32 * 1024) { 9 })
                input.finish()
            }
            assertTrue(offering.await(2, TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { last.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            last.get(2, TimeUnit.SECONDS)
            drain.get(2, TimeUnit.SECONDS)
            assertEquals(10 * 32 * 1024, output.size())
            val result = output.toByteArray()
            repeat(10) { index ->
                assertArrayEquals(ByteArray(32 * 1024) { index.toByte() }, result.copyOfRange(index * 32 * 1024, (index + 1) * 32 * 1024))
            }
        } finally { release.countDown(); input.abort(); workers.shutdownNow() }
    }

    @Test fun `explicit retirement aborts pending bytes even after EOF`() {
        val input = RelayIncoming()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val worker = Executors.newSingleThreadExecutor()
        val drain = worker.submit {
            input.drainTo(object : OutputStream() {
                override fun write(byte: Int) { output.write(byte) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writing.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                    output.write(bytes, offset, length)
                }
            })
        }
        try {
            input.offer("already-writing".toByteArray())
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            input.offer("must-be-discarded".toByteArray())
            input.finish()
            input.abort()
            release.countDown()
            drain.get(2, TimeUnit.SECONDS)
            assertEquals("already-writing", output.toString("UTF-8"))
        } finally { release.countDown(); input.abort(); worker.shutdownNow() }
    }

    @Test fun `EOF drain retains the existing ten second stall bound`() {
        val input = RelayIncoming()
        input.offer("pending".toByteArray())
        input.finish()
        assertFalse(input.isStalled(System.nanoTime()))
        assertTrue(input.isStalled(System.nanoTime() + TimeUnit.SECONDS.toNanos(10)))
        input.abort()
    }

    @Test fun `oversized input is rejected before queue admission`() {
        val input = RelayIncoming()
        assertThrows(java.io.IOException::class.java) { input.offer(ByteArray(32 * 1024 + 1)) }
        input.finish()
        val output = ByteArrayOutputStream()
        input.drainTo(output)
        assertEquals(0, output.size())
    }
}

