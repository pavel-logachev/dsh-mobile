package dev.dshmobile.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationResumeTest {
    @Test fun stopInvalidatesDiskReadBeforeWatchServiceStart() = runTest {
        val disk = CompletableDeferred<Unit>(); var starts = 0; var failures = 0
        val owner = NotificationResume(this, { disk.await(); true }, { starts++ }, { failures++ })
        owner.foreground(true); runCurrent(); owner.foreground(false); disk.complete(Unit); runCurrent()
        assertEquals(0, starts)
        owner.foreground(true); runCurrent(); assertEquals(1, starts); assertEquals(0, failures)
        owner.foreground(false)
    }
    @Test fun platformStartFailureIsSurfacedOnceAndDoesNotEscape() = runTest {
        var failures = 0
        val owner = NotificationResume(this, { true }, { throw SecurityException() }, { failures++ })
        owner.foreground(true); runCurrent(); assertEquals(1, failures); owner.foreground(false)
    }
}
