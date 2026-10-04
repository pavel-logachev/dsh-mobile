package dev.dshmobile.app.ui.pairing

import dev.dshmobile.app.BuildConfig
import dev.dshmobile.app.data.InvitationQrPayload
import dev.dshmobile.app.data.MobileFailure
import dev.dshmobile.app.ui.TrustPreview
import dev.dshmobile.app.ui.InvitationReview

/** Scanner boundary: neither payloads nor platform exception text are safe diagnostics. */
internal sealed interface InvitationScanResult {
    class Scanned(val rawValue: String?) : InvitationScanResult {
        override fun toString() = "Scanned(redacted)"
    }
    data object Cancelled : InvitationScanResult
    data object Unavailable : InvitationScanResult
    data object Failed : InvitationScanResult
}

internal sealed interface PairingScanOutcome {
    class Review(val json: String, val review: InvitationReview) : PairingScanOutcome {
        val preview: TrustPreview get() = review.preview
        override fun toString() = "Review(redacted)"
    }
    data class Error(val key: String) : PairingScanOutcome
    data object Cancelled : PairingScanOutcome
}

/** Only prepares the existing trust dialog. This boundary never connects or issues credentials. */
internal fun handleInvitationScan(result: InvitationScanResult, debug: Boolean = BuildConfig.DEBUG): PairingScanOutcome = when (result) {
    is InvitationScanResult.Scanned -> try {
        val json = InvitationQrPayload.decode(result.rawValue ?: throw MobileFailure("invitation_invalid"))
        PairingScanOutcome.Review(json, InvitationReview.parse(json, debug))
    } catch (failure: MobileFailure) { PairingScanOutcome.Error(failure.key) }
    catch (_: Exception) { PairingScanOutcome.Error("invitation_invalid") }
    InvitationScanResult.Cancelled -> PairingScanOutcome.Cancelled
    InvitationScanResult.Unavailable -> PairingScanOutcome.Error("invitation_scan_unavailable")
    InvitationScanResult.Failed -> PairingScanOutcome.Error("invitation_scan_failed")
}
