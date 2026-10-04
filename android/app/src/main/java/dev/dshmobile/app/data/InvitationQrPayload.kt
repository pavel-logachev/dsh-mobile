package dev.dshmobile.app.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.Inflater

/** Input envelope only, not an alternative invitation parser. Never log the input or exceptions. */
internal object InvitationQrPayload {
    const val MAX_JSON_BYTES = 65_536
    const val MAX_COMPRESSED_BYTES = 65_536
    const val MAX_COMPACT_CHARS = 87_388
    private const val PREFIX = "dshm1:"

    fun decode(value: String): String {
        if (value.length > MAX_COMPACT_CHARS) invalid()
        if (!value.startsWith(PREFIX)) {
            if (value.length > MAX_JSON_BYTES || value.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES ||
                value.firstOrNull { !it.isWhitespace() } != '{') invalid()
            return value
        }
        val encoded = value.substring(PREFIX.length)
        if (encoded.isEmpty() || encoded.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) invalid()
        try {
            val compressed = Base64.getUrlDecoder().decode(encoded)
            if (compressed.size > MAX_COMPRESSED_BYTES || Base64.getUrlEncoder().withoutPadding().encodeToString(compressed) != encoded) invalid()
            val inflater = Inflater(/* nowrap = */ false) // RFC 1950 zlib wrapper, matching Node deflateSync.
            try {
                inflater.setInput(compressed)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (output.size() + count > MAX_JSON_BYTES) invalid()
                    if (count == 0 && !inflater.finished()) invalid() // Truncated, dictionary-dependent or stalled.
                    output.write(buffer, 0, count)
                }
                if (inflater.remaining != 0) invalid() // No suffix, concatenated stream or alternate envelope.
                val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                if (json.firstOrNull { !it.isWhitespace() } != '{') invalid()
                return json
            } finally { inflater.end() }
        } catch (_: Exception) { invalid() }
    }

    private fun invalid(): Nothing = throw MobileFailure("invitation_invalid")
}
