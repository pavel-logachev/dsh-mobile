package dev.dshmobile.app.ui.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dshmobile.app.data.Invitation
import dev.dshmobile.app.ui.InvitationReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Memory-only invitation input boundary; never place it in SavedState or log its contents. */
internal class PairingInput(
    private val scope: CoroutineScope,
    private val pairInvitation: suspend (Invitation, String) -> Unit,
) {
    var invitation by mutableStateOf("")
        private set
    var importError by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    private var revision = 0L
    private var importJob: Job? = null

    /** A new input wins immediately, even if a provider ignores cancellation of its read. */
    fun supersedeImport() {
        revision++
        importJob?.cancel()
        importJob = null
        importing = false
    }

    fun edit(value: String) {
        supersedeImport()
        invitation = value
        importError = null
    }

    fun import(readContent: suspend () -> String?) {
        supersedeImport()
        val version = revision
        importing = true // Synchronous admission: closes the tap-to-coroutine-launch gap.
        importError = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val content = readContent()
                if (version != revision) return@launch
                if (content == null) importError = "invitation_import_failed" else invitation = content
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == revision) importError = "invitation_import_failed"
            } finally {
                if (version == revision) {
                    importing = false
                    importJob = null
                }
            }
        }
        importJob = job
        job.start()
    }

    fun pair(review: InvitationReview, deviceName: String) {
        supersedeImport()
        val exactInvitation = review.invitation // Never reread the mutable text input after confirmation.
        scope.launch { pairInvitation(exactInvitation, deviceName) }
    }
}
