package dev.dshmobile.app.ui

import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuditViewModelFixTest {
    private class Repo : MobileRepository {
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE,
            workspaces = listOf(Workspace("w", "Synthetic", true)),
            capabilities = MobileCapabilities(true, true, true, false, false, true, false, false),
            snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic", "w", 1, false, true), emptyList(), 1, false, "idle")))
        override val acceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
        var forgetCalls = 0
        override suspend fun answer(callId: String, answer: QuestionAnswer) {}
        override suspend fun restore() {}
        override suspend fun refresh() {}
        override suspend fun pair(invitationJson: String, deviceName: String) {}
        override suspend fun pair(invitation: Invitation, deviceName: String) {}
        override suspend fun selectSession(sessionId: String) { state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(session = state.value.snapshot!!.session.copy(id = sessionId))) }
        override suspend fun createSession(workspaceId: String, presetId: String?, requestId: String) {}
        override suspend fun updateDraft(text: String) {}
        override suspend fun sendMessage(text: String) {}
        override suspend fun cancelRun() {}
        override suspend fun resolvePending() {}
        override suspend fun abandonPending() {}
        override suspend fun forget() { forgetCalls++; state.value = MobileState() }
        override suspend fun setForeground(active: Boolean) {}
        override fun close() {}
    }
    @Test fun `AND04 legacy answers bounded across navigation and cleared by forget`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repo(); val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { repo }
        val models = androidx.lifecycle.ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent()
            repeat(100) {
                if (it == 50) { vm.selectSession("other"); runCurrent(); assertTrue(vm.answeredQuestions.isEmpty()) }
                vm.answer("question-$it", QuestionAnswer(emptyList())); runCurrent()
            }
            assertEquals(32, vm.answeredQuestions.size)
            assertFalse(vm.answeredQuestions.containsKey(questionDraftKey("chat", "question-0")))
            vm.forget(); runCurrent(); assertTrue(vm.answeredQuestions.isEmpty())
        } finally { models.clear(); Dispatchers.resetMain() }
    }
    @Test fun `AND01 corrupt notification record cannot block UI destructive forget`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        File(app.noBackupFilesDir, "notifications.enc").writeBytes(byteArrayOf(0, 1, 2))
        val repo = Repo(); val vm = MobileViewModel(app) { repo }
        val models = androidx.lifecycle.ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); vm.forget(); advanceUntilIdle()
            // reset runs on the IO dispatcher; await its observable repository boundary.
            kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(3000) { while (repo.forgetCalls == 0) kotlinx.coroutines.yield() } }
            assertEquals(1, repo.forgetCalls); assertFalse(File(app.noBackupFilesDir, "notifications.enc").exists())
        } finally { models.clear(); Dispatchers.resetMain() }
    }
}
