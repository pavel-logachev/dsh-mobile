package dev.dshmobile.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SnapshotIngressTest {
    @Test fun `a blocked consumer retains only the newest replacement`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Int>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ingress = SnapshotIngress<Int>(scope) { value ->
            seen += value
            if (value == 1) { firstStarted.complete(Unit); releaseFirst.await() }
        }
        try {
            assertTrue(ingress.offer(1))
            withTimeout(5_000) { firstStarted.await() }
            repeat(10_000) { assertTrue(ingress.offer(it + 2)) }
            assertEquals(1, seen.size)
            releaseFirst.complete(Unit)
            withTimeout(5_000) { while (seen.size < 2) delay(5) }
            assertEquals(2, seen.size)
            assertEquals(10_001, seen.last())
        } finally {
            ingress.close()
            scope.cancel()
        }
    }

    @Test fun `a closed ingress refuses new replacements`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ingress = SnapshotIngress<Int>(scope) { }
        ingress.close()
        assertFalse(ingress.offer(1))
        scope.cancel()
    }
}
