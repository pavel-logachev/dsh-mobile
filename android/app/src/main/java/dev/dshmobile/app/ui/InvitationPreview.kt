package dev.dshmobile.app.ui

import dev.dshmobile.app.BuildConfig
import dev.dshmobile.app.data.Invitation
import dev.dshmobile.app.data.InvitationQrPayload
import java.net.URI

/** Presentation-only allowlist; toString remains safe for diagnostics. */
internal data class TrustPreview(
    val endpoint: String,
    val pin: String,
    val customCertificate: Boolean,
    val debugHttp: Boolean,
    val relayOrigin: String? = null,
)

/** Exact immutable invitation shown in a dialog. Memory-only; never saved across recreation. */
internal class InvitationReview private constructor(val invitation: Invitation, val preview: TrustPreview) {
    override fun toString() = "InvitationReview(redacted)"

    companion object {
        fun parse(input: String, debug: Boolean = BuildConfig.DEBUG): InvitationReview {
            val invitation = Invitation.parse(InvitationQrPayload.decode(input), debug)
            return InvitationReview(invitation, previewInvitation(invitation))
        }
    }
}

/** Scan, file and paste share the bounded decoder and the canonical shallow-schema validation. */
internal fun previewInvitation(input: String, debug: Boolean = BuildConfig.DEBUG): TrustPreview =
    InvitationReview.parse(input, debug).preview

private fun previewInvitation(invitation: Invitation): TrustPreview {
    val endpoint = invitation.endpoint
    val relayOrigin = endpoint.relay?.let { relay ->
        val uri = URI(relay.url)
        // Origin only: omit prefixes, route/access IDs and all credentials.
        "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}"
    }
    return TrustPreview(
        endpoint = endpoint.baseUrl,
        pin = endpoint.pinSha256.orEmpty(),
        customCertificate = endpoint.certificatePem != null,
        debugHttp = endpoint.baseUrl.startsWith("http://"),
        relayOrigin = relayOrigin,
    )
}
