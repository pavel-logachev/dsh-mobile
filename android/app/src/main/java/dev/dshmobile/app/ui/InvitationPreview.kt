package dev.dshmobile.app.ui

import dev.dshmobile.app.BuildConfig
import dev.dshmobile.app.data.Invitation
import java.net.URI

/** Presentation-only allowlist. Never retain the parsed invitation or relay credentials in UI state. */
internal data class TrustPreview(
    val endpoint: String,
    val pin: String,
    val customCertificate: Boolean,
    val debugHttp: Boolean,
    val relayOrigin: String? = null,
)

/** Uses the same strict v1/v2 validation as pairing; there is no competing JSON parser. */
internal fun previewInvitation(json: String, debug: Boolean = BuildConfig.DEBUG): TrustPreview {
    val endpoint = Invitation.parse(json, debug).endpoint
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
