package dev.dshmobile.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatMediaCacheTest {
    @Test fun `preview retries one transient fetch failure`() = runTest {
        var calls = 0
        val result = fetchImagePreview {
            calls++
            if (calls == 1) throw IOException("transient")
            byteArrayOf(4, 2)
        }
        assertArrayEquals(byteArrayOf(4, 2), result)
        assertEquals(2, calls)
    }

    @Test fun `preview timeout is bounded and retried once`() = runTest {
        var calls = 0
        try {
            fetchImagePreview { calls++; kotlinx.coroutines.delay(20_000); byteArrayOf(1) }
            fail("stalled preview must not return")
        } catch (expected: kotlinx.coroutines.TimeoutCancellationException) {
            assertEquals(2, calls)
        }
    }

    @Test fun `slow miss does not delay cached image`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cached = byteArrayOf(2)
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) { id ->
            if (id == "A") { started.complete(Unit); release.await(); byteArrayOf(1) } else cached
        }
        assertSame(cached, cache.get("B"))
        val slow = async { cache.get("A") }
        started.await()
        val hit = async { cache.get("B") }
        runCurrent()
        val hitCompletedBeforeRelease = hit.isCompleted
        release.complete(Unit)
        slow.await()
        assertSame(cached, hit.await())
        assertTrue("cache hit waited for an unrelated fetch", hitCompletedBeforeRelease)
        cache.close()
    }

    @Test fun `same image shares one fetch and cancelling first waiter keeps second alive`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        val bytes = byteArrayOf(7)
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) {
            calls++
            try { release.await(); bytes } catch (e: CancellationException) { cancelled = true; throw e }
        }
        val first = async { cache.get("same") }
        val second = async { cache.get("same") }
        runCurrent()
        assertEquals(1, calls)
        first.cancelAndJoin()
        runCurrent()
        assertFalse(cancelled)
        release.complete(Unit)
        assertSame(bytes, second.await())
        assertSame(bytes, cache.get("same"))
        assertEquals(1, calls)
        cache.close()
    }

    @Test fun `distinct misses run two at a time and queued cancellation never calls loader`() = runTest {
        val release = CompletableDeferred<Unit>()
        val called = mutableListOf<String>()
        var active = 0
        var peak = 0
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) { id ->
            called += id
            active++
            peak = maxOf(peak, active)
            try { release.await(); byteArrayOf(1) } finally { active-- }
        }
        val requests = (1..6).map { id -> async { cache.get(id.toString()) } }
        runCurrent()
        assertEquals(listOf("1", "2"), called)
        requests[2].cancelAndJoin()
        runCurrent()
        release.complete(Unit)
        requests.filterIndexed { index, _ -> index != 2 }.forEach { it.await() }
        assertEquals(2, peak)
        assertEquals(setOf("1", "2", "4", "5", "6"), called.toSet())
        cache.close()
    }

    @Test fun `last waiter cancellation cancels fetch and a replacement is not removed by old completion`() = runTest {
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) {
            calls++
            if (calls == 1) {
                try { CompletableDeferred<Unit>().await() }
                catch (_: CancellationException) {
                    cancelled = true
                    withContext(NonCancellable) { oldRelease.await() }
                }
                byteArrayOf(1)
            } else { newRelease.await(); byteArrayOf(2) }
        }
        val old = async { cache.get("same") }
        runCurrent()
        old.cancelAndJoin()
        runCurrent()
        assertTrue(cancelled)
        val replacement = async { cache.get("same") }
        runCurrent()
        oldRelease.complete(Unit)
        runCurrent()
        val shared = async { cache.get("same") }
        runCurrent()
        assertEquals(2, calls)
        newRelease.complete(Unit)
        assertArrayEquals(byteArrayOf(2), replacement.await())
        assertSame(replacement.await(), shared.await())
        assertSame(shared.await(), cache.get("same"))
        cache.close()
    }

    @Test fun `forgotten and abandoned scopes cancel waiters and reject noncooperative late results`() = runTest {
        for (abandoned in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            var returned = false
            val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) {
                withContext(NonCancellable) { release.await() }
                returned = true
                byteArrayOf(1)
            }
            val request = async { cache.get("same") }
            runCurrent()
            if (abandoned) cache.onAbandoned() else cache.onForgotten()
            release.complete(Unit)
            runCurrent()
            assertTrue(returned)
            try { request.await(); fail("closed cache published a late result") } catch (_: CancellationException) { }
            try { cache.get("same"); fail("closed cache returned an entry") } catch (_: CancellationException) { }
        }
    }

    @Test fun `separate session caches never share same ID flights or bytes`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = ImageMemoryCache(StandardTestDispatcher(testScheduler)) { calls++; release.await(); byteArrayOf(1) }
        val second = ImageMemoryCache(StandardTestDispatcher(testScheduler)) { calls++; byteArrayOf(2) }
        val old = async { first.get("same") }
        runCurrent()
        assertArrayEquals(byteArrayOf(2), second.get("same"))
        first.onForgotten()
        release.complete(Unit)
        try { old.await(); fail("old session survived closure") } catch (_: CancellationException) { }
        assertArrayEquals(byteArrayOf(2), second.get("same"))
        assertEquals(2, calls)
        second.close()
    }

    @Test fun `shared errors reach every waiter and next request retries`() = runTest {
        val release = CompletableDeferred<Unit>()
        val failure = IOException("synthetic")
        var calls = 0
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) {
            calls++
            if (calls == 1) { release.await(); throw failure } else byteArrayOf()
        }
        // Catch inside each caller so an expected loader failure does not cancel the test scope.
        val waiters = (1..2).map { async { try { cache.get("same"); null } catch (e: IOException) { e } } }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        waiters.forEach {
            val actual = it.await()
            assertEquals(IOException::class.java, actual?.javaClass)
            assertEquals("synthetic", actual?.message)
        }
        assertArrayEquals(byteArrayOf(), cache.get("same"))
        assertEquals(2, calls)
        cache.close()
    }

    @Test fun `40 MiB cache boundary evicts least recently used bytes`() = runTest {
        val calls = mutableMapOf<String, Int>()
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) { id ->
            calls[id] = calls.getOrDefault(id, 0) + 1
            ByteArray(20 * 1024 * 1024)
        }
        val a = cache.get("A")
        val b = cache.get("B")
        assertSame(a, cache.get("A")) // Exact 40 MiB retains both; A is now most recent.
        assertSame(b, cache.get("B"))
        cache.get("A")
        cache.get("C")
        assertSame(a, cache.get("A"))
        cache.get("B")
        assertEquals(mapOf("A" to 1, "B" to 2, "C" to 1), calls)
        cache.close()
    }

    @Test fun `response over cache budget is returned but not retained`() = runTest {
        var calls = 0
        val cache = ImageMemoryCache(StandardTestDispatcher(testScheduler)) { id ->
            calls++
            if (id == "large") ByteArray(40 * 1024 * 1024 + 1) else byteArrayOf(1)
        }
        val small = cache.get("small")
        assertEquals(40 * 1024 * 1024 + 1, cache.get("large").size)
        assertSame(small, cache.get("small"))
        cache.get("large")
        assertEquals(3, calls)
        cache.close()
    }
}
