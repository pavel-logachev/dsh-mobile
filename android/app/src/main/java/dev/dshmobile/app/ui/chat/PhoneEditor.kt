package dev.dshmobile.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import dev.dshmobile.app.R
import dev.dshmobile.app.data.MobileRepository
import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

internal fun attachmentDisplayName(raw: String, mime: String?, fallback: String, temporary: Boolean = false): String {
    var name = raw.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
    val extension = name.substringAfterLast('.', "").takeIf { name.contains('.') && it.length in 1..12 && it.all(Char::isLetterOrDigit) }
    val stem = if (extension != null) name.dropLast(extension.length + 1) else name
    val meaningless = temporary || stem.none(Char::isLetter) || stem.lowercase() in setOf("attachment", "image", "photo", "file", "unnamed", "unknown")
    if (mime?.startsWith("image/") == true && meaningless) {
        val suffix = extension ?: when (mime) { "image/jpeg" -> "jpeg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "" }
        name = fallback + if (suffix.isNotEmpty()) ".$suffix" else ""
    }
    if (name.isBlank()) name = "attachment"
    val suffix = extension?.let { ".$it" }.orEmpty()
    var boundedStem = if (suffix.isNotEmpty() && name.endsWith(suffix)) name.dropLast(suffix.length) else name
    while ((boundedStem + suffix).toByteArray(Charsets.UTF_8).size > 256) {
        val last = boundedStem.lastIndex
        boundedStem = boundedStem.dropLast(if (last > 0 && boundedStem[last].isLowSurrogate() && boundedStem[last - 1].isHighSurrogate()) 2 else 1)
    }
    return boundedStem + suffix
}

/** ViewModel-owned editor: URI metadata is encrypted by the repository, bytes never enter saved state. */
internal class PhoneEditor(private val context: Context, private val repository: MobileRepository, private val scope: CoroutineScope) {
    private val uploads = Semaphore(2)
    private val writes = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    var items by mutableStateOf<Map<String, List<UploadState>>>(emptyMap()); private set
    var error by mutableStateOf<Int?>(null); private set
    var skippedLarge by mutableIntStateOf(0); private set
    private var cleaned = false
    private var previousDrafts = emptyMap<String, List<DraftAttachment>>()
    private var knownSources = emptyList<DraftAttachment>()
    init {
        // Safe before encrypted draft restore: only expired exports, never camera sources.
        scope.launch(Dispatchers.IO) {
            TemporaryMedia.directory(context).listFiles()?.filter { it.name.startsWith("share-") && System.currentTimeMillis() - it.lastModified() >= TemporaryMedia.SHARE_TTL_MS }?.forEach { TemporaryMedia.delete(context, it) }
        }
    }
    fun restore(state: MobileState) {
        if (!state.draftsRestored) return
        val retained = state.attachmentDrafts.values.flatten() + state.retainedAttachmentSources
        val releasedSources = knownSources.filter { old -> retained.none { it.uri == old.uri } }
        knownSources = retained
        if (releasedSources.isNotEmpty()) scope.launch(Dispatchers.IO) { releasedSources.forEach { clean(it) } }
        if (!state.paired) {
            jobs.values.forEach { it.cancel() }; jobs.clear()
            val previous = items.values.flatten().map { it.attachment }
            scope.launch(Dispatchers.IO) { previous.forEach { clean(it) } }
            items = emptyMap(); previousDrafts = emptyMap(); cleaned = false; error = null; return
        }
        val removed = previousDrafts.flatMap { (session, old) ->
            val currentIds = state.attachmentDrafts[session].orEmpty().map { it.localId }.toSet()
            old.filter { it.localId !in currentIds }.also { gone ->
                val ids = gone.map { it.localId }.toSet()
                gone.forEach { jobs.remove(it.localId)?.cancel() }
                items = items + (session to inSession(session).filterNot { it.attachment.localId in ids })
            }
        }
        previousDrafts = state.attachmentDrafts
        state.attachmentDrafts.forEach { (session, attachments) ->
            val existing = inSession(session).map { it.attachment.localId }.toSet()
            val persisted = attachments.associateBy { it.localId }
            val current = inSession(session).map { upload ->
                val source = persisted[upload.attachment.localId]
                if (!upload.uploading && source?.uploadId != null && (source.uploadId != upload.attachment.uploadId || source.expiresAt != upload.attachment.expiresAt))
                    upload.copy(attachment = source, progress = 100, error = false) else upload
            }
            items = items + (session to (current + attachments.filter { it.localId !in existing }.map { UploadState(it, if (it.uploadId != null) 100 else 0, error = it.uploadId == null) }))
        }
        if (removed.isNotEmpty()) scope.launch(Dispatchers.IO) { removed.forEach { clean(it) } }
        if (!cleaned) {
            cleaned = true
            scope.launch(Dispatchers.IO) { TemporaryMedia.cleanup(context, retained) }
        }
    }
    fun inSession(id: String?) = items[id].orEmpty()
    private fun update(session: String, id: String, change: (UploadState) -> UploadState) {
        items = items + (session to inSession(session).map { if (it.attachment.localId == id) change(it) else it })
    }
    private suspend fun persist(session: String, add: List<DraftAttachment> = emptyList(), remove: Set<String> = emptySet(),
        replace: DraftAttachment? = null, expectedUploadId: String? = null) = writes.withLock {
        repository.editAttachments(session, add, remove, replace, expectedUploadId)
        // Keep cleanup correct even outside the ViewModel's state collector (tests/explicit clear).
        knownSources = repository.state.value.attachmentDrafts.values.flatten() + repository.state.value.retainedAttachmentSources
    }
    fun pick(session: String, uris: List<Uri>, temporary: Boolean = false) {
        dev.dshmobile.app.data.notificationTrace("media_editor_pick count=${uris.size} temporary=$temporary")
        scope.launch {
            error = null; skippedLarge = 0
            var large = 0
            for (uri in uris) {
                var convertedSource: DraftAttachment? = null
                try {
                    val item = withContext(Dispatchers.IO) {
                        var name = "attachment"; var size = -1L
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                                if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                            }
                        }
                        // Some cloud providers omit length. Count a bounded stream without retaining it.
                        if (size < 0) context.contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(32768); size = 0
                            while (size <= PhoneRules.FILE_LIMIT) { val n = input.read(buffer); if (n < 0) break; size += n }
                        }
                        val mime = context.contentResolver.getType(uri)
                        val fallback = context.getString(R.string.mobile_attach_photo_name, inSession(session).size + 1)
                        if (HeifUpload.matches(mime, name) && size in 1..PhoneRules.FILE_LIMIT) {
                            val (converted, bytes) = HeifUpload.convert(context, uri)
                            val jpegName = attachmentDisplayName(name.substringBeforeLast('.', name) + ".jpg", "image/jpeg", fallback, temporary = temporary)
                            DraftAttachment(UUID.randomUUID().toString(), converted.toString(), jpegName, bytes, "image/jpeg", temporary = true).also { convertedSource = it }
                        } else {
                            name = attachmentDisplayName(name, mime, fallback, temporary = temporary)
                            DraftAttachment(UUID.randomUUID().toString(), uri.toString(), name, size, PhoneRules.uploadMime(mime), temporary = temporary)
                        }
                    }
                    if (!PhoneRules.canAddAttachment(inSession(session).map { it.attachment.size }, item.size)) {
                        if (item.size > PhoneRules.FILE_LIMIT) { large++; skippedLarge = large }
                        error = if (large > 0) R.plurals.mobile_attach_skipped_too_large else if (inSession(session).size >= 20) R.string.mobile_attach_images_limit else R.string.mobile_attach_too_large_total
                        clean(item)
                        continue
                    }
                    if (!item.temporary) runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    items = items + (session to (inSession(session) + UploadState(item)))
                    convertedSource = null // Draft/editor now owns cleanup, including an uncertain send.
                    dev.dshmobile.app.data.notificationTrace("media_editor_chip bytes=${item.size} temporary=${item.temporary}")
                    persist(session, add = listOf(item)); retry(session, item.localId)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = R.string.mobile_attach_read_failed }
                finally { convertedSource?.let { source -> withContext(NonCancellable + Dispatchers.IO) { clean(source) } } }
            }
            if (large > 0) { skippedLarge = large; error = R.plurals.mobile_attach_skipped_too_large }
        }
    }
    fun retry(session: String, id: String) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch {
            error = null; skippedLarge = 0
            update(session, id) { it.copy(uploading = true, error = false, progress = -1) }
            try {
                uploads.withPermit {
                    val source = inSession(session).singleOrNull { it.attachment.localId == id }?.attachment ?: return@withPermit
                    val receipt = repository.upload(session, source, {
                        context.contentResolver.openInputStream(Uri.parse(source.uri)) ?: throw java.io.IOException("unavailable")
                    }) { percent -> scope.launch { update(session, id) { it.copy(progress = percent) } } }
                    update(session, id) { it.copy(attachment = receipt, uploading = false, progress = 100, error = false) }
                    persist(session, replace = receipt, expectedUploadId = source.uploadId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                dev.dshmobile.app.data.UploadDiagnostic.record("editor", dev.dshmobile.app.data.UploadDiagnostic.code(failure))
                if ((failure as? dev.dshmobile.app.data.MobileFailure)?.key in setOf("rate_limited", "staging_full")) error = R.string.mobile_attach_rate_limited
                update(session, id) { it.copy(uploading = false, error = true) }
            }
        }
    }
    fun remove(session: String, id: String) {
        error = null; skippedLarge = 0
        jobs.remove(id)?.cancel()
        val old = inSession(session).find { it.attachment.localId == id }?.attachment
        items = items + (session to inSession(session).filterNot { it.attachment.localId == id })
        scope.launch { persist(session, remove = setOf(id)); old?.let { clean(it) } }
    }
    fun clear(session: String) {
        error = null; skippedLarge = 0
        val previous = inSession(session); previous.forEach { jobs.remove(it.attachment.localId)?.cancel() }
        items = items + (session to emptyList())
        scope.launch { persist(session, remove = previous.map { it.attachment.localId }.toSet()); previous.forEach { clean(it.attachment) } }
    }
    private suspend fun clean(item: DraftAttachment) = withContext(Dispatchers.IO) {
        val referenced = (items.values.flatten().map { it.attachment } + knownSources).any { it.uri == item.uri }
        if (referenced) return@withContext
        if (!item.temporary) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(item.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // FileProvider controls the directory; never delete arbitrary external URI paths.
        if (item.temporary) runCatching { TemporaryMedia.delete(context, java.io.File(TemporaryMedia.directory(context), Uri.parse(item.uri).lastPathSegment.orEmpty())) }
    }
    fun ready(session: String) = inSession(session).all { !it.uploading && !it.error && it.attachment.uploadId != null && it.attachment.expiresAt > System.currentTimeMillis() }
    suspend fun prepareSend(session: String, expectedIds: List<String>): Boolean {
        val expired = inSession(session).filter { it.attachment.expiresAt <= System.currentTimeMillis() }
        expired.forEach { retry(session, it.attachment.localId) }
        expired.mapNotNull { jobs[it.attachment.localId] }.joinAll()
        return inSession(session).map { it.attachment.localId } == expectedIds && ready(session)
    }
}
