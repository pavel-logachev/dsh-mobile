package dev.dshmobile.app.data

import dev.dshmobile.app.model.PendingCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
internal class PairedHost(
    val endpoint: HostEndpoint,
    val deviceId: String,
    val deviceToken: String,
    val hostName: String,
)

/** One encrypted atomic record: admission ID, exact payload and draft cannot tear apart. */
@Serializable
internal data class StoredState(
    val host: PairedHost? = null,
    val selectedSessionId: String? = null,
    val drafts: Map<String, String> = emptyMap(),
    val pending: StoredCommand? = null,
    val notSentSessions: Set<String> = emptySet(),
    val acceptedPrompts: List<StoredCommand> = emptyList(),
    val attachmentDrafts: Map<String, List<dev.dshmobile.app.model.DraftAttachment>> = emptyMap(),
    @kotlinx.serialization.SerialName("answerDraftsByQuestion")
    val answerDrafts: Map<String, dev.dshmobile.app.model.QuestionAnswer> = emptyMap(),
    // Staging TTL tombstones survive abandoning/dismissing the command which may have used them.
    val possiblyUsedUploads: Map<String, Long> = emptyMap(),
    val uploadProtectionUntil: Long = 0,
)

@Serializable
internal data class StoredCommand(
    val requestId: String,
    val kind: String,
    val sessionId: String? = null,
    val text: String? = null,
    val workspaceId: String? = null,
    val presetId: String? = null,
    val expectedCursor: Long? = null,
    val status: String = "sending",
    val queuedWhileRunning: Boolean = false,
    val acceptedAt: Long? = null,
    val idleSnapshots: Int = 0,
    val lastIdleCursor: Long? = null,
    val delivery: String? = null,
    val uploadIds: List<String>? = null,
    val itemId: String? = null, val action: String? = null,
    val title: String? = null,
    val selection: dev.dshmobile.app.model.ModelSelection? = null,
    val callId: String? = null, val answer: dev.dshmobile.app.model.QuestionAnswer? = null,
    val skip: Boolean = false,
    val attachmentSources: List<dev.dshmobile.app.model.DraftAttachment> = emptyList(),
) {
    // Sources are already persisted with the exact command. Legacy commands default to no names.
    fun presentation() = PendingCommand(requestId, kind, sessionId, text, status, uploadIds.orEmpty().size, delivery,
        attachmentSources.take(20).mapNotNull { source ->
            var name = source.name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
            while (name.toByteArray(Charsets.UTF_8).size > 256) {
                name = name.dropLast(if (name.length > 1 && name.last().isLowSurrogate() && name[name.lastIndex - 1].isHighSurrogate()) 2 else 1)
            }
            name.takeIf { it.isNotBlank() }
        })
}

internal fun validAttachmentDraft(item: dev.dshmobile.app.model.DraftAttachment): Boolean =
    validId(item.localId) && item.uri.startsWith("content://") && item.uri.length <= 8192 &&
        item.name.isNotBlank() && item.name.toByteArray().size <= 256 && item.name.none { it.isISOControl() } &&
        item.size in 1..dev.dshmobile.app.model.PhoneRules.FILE_LIMIT && item.expiresAt >= 0 &&
        (item.uploadId == null || validId(item.uploadId))

internal fun validAnswerDraft(answer: dev.dshmobile.app.model.QuestionAnswer): Boolean =
    answer.answers.size in 1..20 && answer.answers.map { it.id }.distinct().size == answer.answers.size && answer.answers.all { a ->
        validId(a.id) && a.selected.size <= 100 && a.selected.distinct().size == a.selected.size &&
            a.selected.all { it.toByteArray().size <= 4096 } &&
            (a.custom == null || (a.custom.toByteArray().size <= 32768 && '\u0000' !in a.custom))
    }

private fun validQuestionDraftKey(key: String): Boolean {
    val separator = key.indexOf(':')
    if (separator < 1) return false
    val length = key.substring(0, separator).toIntOrNull() ?: return false
    if (length !in 1..512 || key.length <= separator + 1 + length) return false
    return validId(key.substring(separator + 1, separator + 1 + length)) && validId(key.substring(separator + 1 + length))
}

/** Parse draft entries independently so one malformed new draft cannot destroy the record. */
internal fun decodeStoredState(text: String): StoredState {
    val root = mobileJson.parseToJsonElement(text).jsonObject
    fun commandSources(value: JsonElement): JsonElement {
        val command = value as? JsonObject ?: return value
        val sources = (command["attachmentSources"] as? JsonArray).orEmpty().take(20).mapNotNull {
            runCatching { mobileJson.decodeFromJsonElement<dev.dshmobile.app.model.DraftAttachment>(it) }.getOrNull()?.takeIf(::validAttachmentDraft)
        }
        val safe = command.toMutableMap()
        // Optional phone metadata is independently decoded. Required mutation payload remains
        // fail-closed in validateStoredCommand; never invent or replay a damaged command.
        fun optional(name: String, check: (JsonElement) -> Boolean) {
            safe[name]?.let { if (it != JsonNull && !check(it)) safe.remove(name) }
        }
        optional("delivery") { it is JsonPrimitive && it.isString && it.content in setOf("queue", "steer") }
        for (name in listOf("itemId", "callId")) optional(name) { it is JsonPrimitive && it.isString && validId(it.content) }
        optional("action") { it is JsonPrimitive && it.isString && it.content in setOf("steer", "remove") }
        optional("title") { it is JsonPrimitive && it.isString && dev.dshmobile.app.model.PhoneRules.validTitle(it.content) }
        optional("uploadIds") { it is JsonArray && it.size <= 20 && it.all { id -> id is JsonPrimitive && id.isString && validId(id.content) } }
        optional("selection") { runCatching { mobileJson.decodeFromJsonElement<dev.dshmobile.app.model.ModelSelection>(it) }.isSuccess }
        optional("answer") { runCatching { mobileJson.decodeFromJsonElement<dev.dshmobile.app.model.QuestionAnswer>(it) }.isSuccess }
        optional("skip") { it is JsonPrimitive && !it.isString && it.booleanOrNull != null }
        // skip is non-nullable; explicit null is also a malformed optional value.
        if (safe["skip"] == JsonNull) safe.remove("skip")
        return JsonObject(safe + ("attachmentSources" to mobileJson.encodeToJsonElement(sources)))
    }
    val fields = root.filterKeys { it !in setOf("drafts", "attachmentDrafts", "answerDrafts", "answerDraftsByQuestion", "possiblyUsedUploads", "uploadProtectionUntil", "notSentSessions") }.toMutableMap()
    fields["pending"]?.let { fields["pending"] = commandSources(it) }
    (fields["acceptedPrompts"] as? JsonArray)?.let { fields["acceptedPrompts"] = JsonArray(it.map(::commandSources)) }
    val core = JsonObject(fields)
    fun entries(name: String) = (root[name] as? JsonObject).orEmpty().entries.toList().takeLast(64)
    val drafts = entries("drafts").mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
    }.toMap()
    val attachments = entries("attachmentDrafts").mapNotNull { (key, value) ->
        (value as? JsonArray)?.let { array -> key to array.take(20).mapNotNull { runCatching { mobileJson.decodeFromJsonElement<dev.dshmobile.app.model.DraftAttachment>(it) }.getOrNull() } }
    }.toMap()
    // Legacy callId-only entries have no safe session identity; deliberately drop them.
    val answers = entries("answerDraftsByQuestion").mapNotNull { (key, value) ->
        runCatching { key to mobileJson.decodeFromJsonElement<dev.dshmobile.app.model.QuestionAnswer>(value) }.getOrNull()
    }.toMap()
    val protection = (root["possiblyUsedUploads"] as? JsonObject).orEmpty().mapNotNull { (id, value) ->
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 && validId(id) }?.let { id to it }
    }.toMap()
    val until = (root["uploadProtectionUntil"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: 0
    return mobileJson.decodeFromJsonElement<StoredState>(core).copy(drafts = drafts, attachmentDrafts = attachments, answerDrafts = answers,
        notSentSessions = (root["notSentSessions"] as? JsonArray).orEmpty().takeLast(64).mapNotNull {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString && validId(value.content) }?.content
        }.toSet(), possiblyUsedUploads = protection, uploadProtectionUntil = until).sanitizeDrafts()
}

/** Draft corruption is local to that draft, never to the paired host or pending command. */
internal fun StoredState.sanitizeDrafts(now: Long = System.currentTimeMillis()): StoredState = copy(
    notSentSessions = notSentSessions.filter { validId(it) && it in drafts }.takeLast(64).toSet(),
    possiblyUsedUploads = possiblyUsedUploads.filter { validId(it.key) && it.value > now }.entries.toList().takeLast(1280).associate { it.toPair() },
    uploadProtectionUntil = maxOf(uploadProtectionUntil.takeIf { it > now } ?: 0,
        if (possiblyUsedUploads.count { validId(it.key) && it.value > now } > 1280) now + 600_000 else 0),
    drafts = drafts.filter { validId(it.key) && it.value.toByteArray().size <= 32768 }.entries.toList().takeLast(64).associate { it.toPair() },
    answerDrafts = answerDrafts.filter { validQuestionDraftKey(it.key) && validAnswerDraft(it.value) }.entries.toList().takeLast(64).associate { it.toPair() },
    acceptedPrompts = acceptedPrompts.map { it.copy(attachmentSources = it.attachmentSources.filter(::validAttachmentDraft).take(20)) },
    pending = pending?.let { it.copy(attachmentSources = it.attachmentSources.filter(::validAttachmentDraft).take(20)) },
    attachmentDrafts = attachmentDrafts.filterKeys(::validId).entries.toList().takeLast(64).mapNotNull { (id, attachments) ->
        var total = 0L
        val safe = attachments.filter(::validAttachmentDraft).take(20).filter { a ->
            (total + a.size <= dev.dshmobile.app.model.PhoneRules.MESSAGE_LIMIT).also { if (it) total += a.size }
        }.distinctBy { it.localId }
        if (safe.isEmpty()) null else id to safe
    }.toMap(),
)

/** Durable device storage boundary. Implementations must commit complete records atomically. */
internal interface SecureStateStore {
    suspend fun read(): StoredState
    suspend fun write(value: StoredState)
    suspend fun clear()
    /** Destructive last resort: delete the record even when the key cannot encrypt/decrypt. */
    suspend fun reset() { clear() }
}
