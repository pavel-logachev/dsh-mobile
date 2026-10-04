package dev.dshmobile.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TransportIoOwnerTest {
    @Test fun `retirement reserves terminal cleanup even when ordinary queue is full`() = runBlocking {
        val owner = TransportIoOwner()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()
        val terminalThread = AtomicReference<Thread>()
        assertTrue(owner.submit { started.countDown(); release.await(5, TimeUnit.SECONDS) })
        assertTrue(started.await(2, TimeUnit.SECONDS))
        repeat(8) { assertTrue(owner.submit { completed.incrementAndGet() }) }
        assertFalse(owner.submit { fail("Queue overflow must not add work") })
        val caller = Thread.currentThread()
        owner.close { terminalThread.set(Thread.currentThread()); completed.addAndGet(100) }
        assertFalse(owner.submit { fail("Retired owner must reject work") })
        release.countDown()
        owner.awaitClosed()
        assertEquals(100, completed.get())
        assertNotSame(caller, terminalThread.get())
        terminalThread.get().join(1000)
        assertFalse("Owned worker exits after terminal cleanup", terminalThread.get().isAlive)
    }
    @Test fun `terminal failure completes waiter exceptionally and owner thread exits`() = runBlocking {
        val owner = TransportIoOwner()
        owner.close { throw java.io.IOException("Synthetic cleanup failure") }
        try { owner.awaitClosed(); fail("Must surface physical cleanup failure") }
        catch (expected: java.io.IOException) { }
        assertFalse(owner.submit { fail("Closed owner must not restart") })
    }
}
