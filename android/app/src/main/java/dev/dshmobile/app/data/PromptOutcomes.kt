package dev.dshmobile.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable internal data class PromptOutcome(val requestId: String, val status: String, val updatedAt: String,
    val turnId: String? = null, val summary: String? = null)
@Serializable internal data class PromptOutcomes(val token: String, val outcomes: List<PromptOutcome>)

/** Validate the endpoint projection, not the session transcript. Optional really means optional;
 * unknown has no inferred turn, and an opaque token has no client-invented syntax. */
internal fun decodePromptOutcomes(text: String, requested: List<String>): PromptOutcomes {
    fun invalid(): Nothing = throw MobileFailure("invalid_response")
    val root = try { mobileJson.parseToJsonElement(text) as? JsonObject ?: invalid() }
        catch (_: Exception) { invalid() }
    fun string(obj: JsonObject, name: String, optional: Boolean = false): String? {
        val value = obj[name] ?: return if (optional) null else invalid()
        if (value !is JsonPrimitive || !value.isString) invalid()
        return value.content
    }
    val token = string(root, "token")!!
    val rows = root["outcomes"] as? JsonArray ?: invalid()
    if (rows.size != requested.size) invalid()
    val outcomes = rows.mapIndexed { index, element ->
        val obj = element as? JsonObject ?: invalid()
        val id = string(obj, "requestId")!!
        val status = string(obj, "status")!!
        val at = string(obj, "updatedAt")!!
        val turn = string(obj, "turnId", true)
        val summary = string(obj, "summary", true)
        if (!validRequestId(id) || id != id.lowercase() || id != requested[index] ||
            status !in setOf("unknown", "queued", "running", "waiting", "completed", "failed", "rejected") ||
            !at.endsWith("Z") || runCatching { Instant.parse(at) }.isFailure ||
            (summary != null && (status != "failed" || summary.toByteArray(Charsets.UTF_8).size > 2048 || !summary.validUnicode()))) invalid()
        PromptOutcome(id, status, at, turn, summary)
    }
    return PromptOutcomes(token, outcomes)
}
private fun String.validUnicode(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i++]
        if (c.isHighSurrogate()) { if (i == length || !this[i++].isLowSurrogate()) return false }
        else if (c.isLowSurrogate()) return false
    }
    return true
}
