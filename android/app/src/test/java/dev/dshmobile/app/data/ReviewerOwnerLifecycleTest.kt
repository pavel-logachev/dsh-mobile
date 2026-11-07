package dev.dshmobile.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReviewerOwnerLifecycleTest {
    private class Store : SecureStateStore {
        val entered = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null
        var reads = 0
        override suspend fun read(): StoredState { reads++; entered.complete(Unit); gate?.await(); return StoredState() }
        override suspend fun write(value: StoredState) {}
        override suspend fun clear() {}
    }
    @Test fun `surviving owner finishes restore when initial owner start is cancelled`() = runBlocking {
        val store = Store().apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val repo = NetworkMobileRepository(store, scope, true)
        val a = Any(); val b = Any()
        try {
            val startingA = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(true, a) }
            store.entered.await()
            repo.setForeground(true, b)
            startingA.cancelAndJoin()
            store.gate!!.complete(Unit)
            repo.setForeground(false, a)
            assertTrue("Surviving B must not suppress recovery of the cancelled initial restore", repo.state.value.draftsRestored)
        } finally { repo.close(); scope.cancel() }
    }
    @Test fun `last owner stop cancels repository repair without a ghost foreground owner`() = runBlocking {
        val store = Store().apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var registered = 0; var unregistered = 0
        val repo = NetworkMobileRepository(store, scope, true, networkWake = { registered++; { unregistered++; Unit } })
        val owner = Any()
        try {
            val starting = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(true, owner) }
            store.entered.await()
            starting.cancelAndJoin()
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false, owner) }
            store.gate!!.complete(Unit)
            withTimeout(1000) { stopping.await() }
            assertEquals(registered, unregistered)
            repo.setForeground(true, Any())
            assertTrue(repo.state.value.draftsRestored)
            assertEquals(registered - 1, unregistered)
        } finally { repo.close(); scope.cancel() }
    }

    @Test fun `pending owner transitions identity mixing duplicate stop and close unregister safely`() = runBlocking {
        val store = Store().apply { gate = CompletableDeferred() }
        var registered = 0; var unregistered = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val repo = NetworkMobileRepository(store, scope, true, networkWake = { registered++; { unregistered++; Unit } })
        data class Owner(val value: Int)
        val a = Owner(1); val b = Owner(1)
        try {
            val start = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(true, a) }
            store.entered.await()
            val stopA = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false, a) }
            val startB = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(true, b) }
            val duplicateStopA = async(start = CoroutineStart.UNDISPATCHED) { repo.setForeground(false, a) }
            store.gate!!.complete(Unit)
            start.await(); stopA.await(); startB.await(); duplicateStopA.await()
            assertTrue(repo.state.value.draftsRestored)
            assertEquals(registered - 1, unregistered)
            repo.setForeground(true)
            repo.setForeground(false, b)
            assertEquals(registered - 1, unregistered)
            repo.setForeground(false)
            assertEquals(registered, unregistered)
            repo.setForeground(false, b)
            assertEquals(registered, unregistered)
            repo.setForeground(true, a)
            repo.setForeground(true, b)
            repo.setForeground(false, a)
            assertEquals(registered - 1, unregistered)
            repo.close()
            assertEquals(registered, unregistered)
            repo.setForeground(true, b)
            assertEquals(registered, unregistered)
        } finally { repo.close(); scope.cancel() }
    }
}
