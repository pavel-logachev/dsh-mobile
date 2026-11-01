package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class HostApi(val endpoint: HostEndpoint, private val token: String? = null) {
    private val io = TransportIoOwner()
    private val retired = java.util.concurrent.atomic.AtomicBoolean()
    private val admission = Any()
    private val relayProxy = endpoint.relay?.let { RelayLoopbackProxy(endpoint, io, ::close) }
    private val client: OkHttpClient = try { SecureTransport.client(endpoint, relayProxy) }
        catch (failure: Exception) { relayProxy?.retire(); io.close { relayProxy?.closePhysical() }; throw failure }
    private val eventClient = client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .addInterceptor(BoundedEventSource()).build()
    // Notification-only hook: host comments every 15s (configured maximum 20s).
    // Keep mutation and session-stream transport budgets unchanged.
    private val notificationClient = client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(NOTIFICATION_IDLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .addInterceptor(BoundedEventSource(128 * 1024L)).build()
    private val base = endpoint.baseUrl.toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(vararg segments: String): Request.Builder {
        if (retired.get()) throw MobileFailure("network_unavailable")
        val url = base.newBuilder().addPathSegment("v1").apply { segments.forEach { addPathSegment(it) } }.build()
        return Request.Builder().url(url).header("Accept", "application/json").apply {
            token?.let { header("Authorization", "Bearer $it") }
        }
    }
    private suspend fun execute(request: Request, limit: Int = 64 * 1024): HttpResult {
        if (request.method != "GET") return executeOnce(request, limit)
        // Two physical calls at most, sharing the original 15-second logical read budget.
        return withTimeoutOrNull(15_000L) {
            try { executeOnce(request, limit, recoverClosedRead = true) }
            catch (failure: ReadConnectionClosed) {
                currentCoroutineContext().ensureActive()
                // Parallel metadata can leave several expired H1 relay tunnels in the pool.
                // A second pooled call must not spend the only recovery on another known-stale
                // idle candidate. evictAll leaves allocated GET/SSE connections untouched.
                withContext(Dispatchers.IO) { client.connectionPool.evictAll() }
                executeOnce(request, limit, retryOf = failure.call)
            }
        } ?: throw MobileFailure("network_unavailable")
    }
    private suspend fun executeOnce(request: Request, limit: Int, recoverClosedRead: Boolean = false, retryOf: Call? = null): HttpResult = suspendCancellableCoroutine { continuation ->
        synchronized(admission) {
            if (!continuation.isActive) return@suspendCancellableCoroutine
            if (retired.get() || retryOf?.isCanceled() == true) {
                continuation.resumeWithException(MobileFailure("network_unavailable"))
                return@suspendCancellableCoroutine
            }
            if (retryOf != null) traceReadRetry()
            // A private pool per mutation cannot inherit an idle dead H1 tunnel, nor race a
            // concurrent GET returning to the shared pool. Retire it only AFTER draining the
            // response (zero-idle eviction can close an HTTP2 exchange before body consumption).
            // GET/SSE reuse stays untouched. No recovery/replay is permitted for mutations.
            val mutationPool = if (request.method == "GET") null else okhttp3.ConnectionPool()
            val headersAttempted = java.util.concurrent.atomic.AtomicBoolean()
            val callClient = mutationPool?.let { pool -> client.newBuilder().connectionPool(pool)
                .eventListener(object : okhttp3.EventListener() {
                    override fun requestHeadersStart(call: Call) { headersAttempted.set(true) }
                }).build() } ?: client
            val singleRequest = if (request.method == "GET" || request.body == null) request else {
                val body = request.body!!
                request.newBuilder().method(request.method, object : okhttp3.RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true // Also prohibit HTTP follow-up replay (408/503).
                    override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
                }).build()
            }
            val call = callClient.newCall(singleRequest)
            continuation.invokeOnCancellation { cancelOnIo { call.cancel(); mutationPool?.evictAll() } }
            call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mutationPool?.evictAll()
                val headerEof = e.javaClass == IOException::class.java && e.cause is java.io.EOFException
                val socketClosed = e.javaClass == java.net.SocketException::class.java
                if (request.method == "GET") traceReadFailure(headerEof, socketClosed, continuation.isActive, call.isCanceled(), retired.get())
                if (continuation.isActive) continuation.resumeWithException(
                    if (recoverClosedRead && (headerEof || socketClosed) && !call.isCanceled() && !retired.get()) ReadConnectionClosed(call)
                    else MobileFailure(if (e is SSLException) "tls_failed" else "network_unavailable",
                        requestNotSent = mutationPool != null && !headersAttempted.get()))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code in 300..399) throw MobileFailure("invalid_response")
                        // Upload DELETE succeeds with an empty 204 and no JSON media type.
                        if (request.method == "DELETE" && it.code == 204) return@use HttpResult(204, "", 0)
                        val body = it.body ?: throw MobileFailure("invalid_response")
                        val media = body.contentType()
                        if (media?.type != "application" || media.subtype != "json" ||
                            media.charset(Charsets.UTF_8) != Charsets.UTF_8) throw MobileFailure("invalid_response")
                        if (body.contentLength() > limit) throw MobileFailure("invalid_response")
                        val bytes = body.byteStream().use { stream -> stream.readAtMost(limit + 1) }
                        if (bytes.size > limit) throw MobileFailure("invalid_response")
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        HttpResult(it.code, text, retryAfterMillis(it.header("Retry-After")))
                    }
                    if (token != null && result.status in 200..299) StartupPerf.mark("first_host_response")
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is MobileFailure) failure else MobileFailure(if (failure is IOException) "network_unavailable" else "invalid_response"))
                } finally { mutationPool?.evictAll() }
            }
            })
        }
    }

    private fun HttpResult.checked(history: Boolean = false): String {
        if (status in 200..299) return text
        throw errorFailure(history)
    }
    private fun HttpResult.errorFailure(history: Boolean = false): MobileFailure {
        val error = decodeWire<ErrorEnvelope>(text).error
        return MobileFailure(when (status) {
            401 -> if (error.code == "revoked") "revoked" else "unauthorized"
            403 -> "forbidden"
            409 -> if (error.code == "question_expired") "question_expired" else "request_conflict"
            429 -> "rate_limited"
            else -> if (status in 400..599 && history) "chat_unavailable" else hostErrorKey(error.code)
        }, retryAfterMs, retryable = error.retryable)
    }

    suspend fun pair(secret: String, name: String): PairingResponse {
        val result = execute(request("pairings").post(mobileJson.encodeToString(PairingBody(secret, name)).toRequestBody(jsonType)).build())
        if (result.status == 401) { decodeWire<ErrorEnvelope>(result.text); throw MobileFailure("pairing_failed") }
        if (result.status != 201) { result.checked(); throw MobileFailure("invalid_response") }
        val decoded = try { decodeWire<PairingResponse>(result.text) }
            catch (failure: Exception) { safeDebugDiagnostic("pair.decode-response", failure); throw failure }
        return decoded.also {
            if (it.protocolVersion != 1) throw MobileFailure("incompatible_protocol")
            if (!validId(it.deviceId) || !safeToken(it.deviceToken) || it.hostName.isBlank() || it.hostName.length > 512) throw MobileFailure("invalid_response")
            if (endpoint.relay != null) RelayPolicy.checkedAccess(it.relayAccess ?: throw MobileFailure("invalid_response"), System.currentTimeMillis())
            else if (it.relayAccess != null) throw MobileFailure("invalid_response")
        }
    }
    suspend fun capabilities(): CapabilitiesResponse = decodeWire<CapabilitiesResponse>(execute(request("capabilities").build()).checked()).also {
        if (it.protocolVersion != 1) throw MobileFailure("incompatible_protocol")
        if (it.hostName.isBlank() || it.hostName.length > 512 || it.upstreamVersion.isBlank() || it.upstreamVersion.length > 128) throw MobileFailure("invalid_response")
    }
    suspend fun workspaces(): List<dev.dshmobile.app.model.Workspace> = decodeWire<WorkspaceResponse>(execute(request("workspaces").build()).checked()).items.also {
        if (it.size > 100 || it.map { item -> item.id }.distinct().size != it.size || it.any { item -> !validId(item.id) || item.name.length > 512 }) throw MobileFailure("invalid_response")
    }
    suspend fun presets(): List<dev.dshmobile.app.model.Preset> = decodeWire<PresetResponse>(execute(request("presets").build()).checked()).items.also {
        if (it.size > 100 || it.map { item -> item.id }.distinct().size != it.size || it.any { item -> !validId(item.id) || item.name.length > 512 }) throw MobileFailure("invalid_response")
    }
    suspend fun sessions(): SessionIndex {
        val items = mutableListOf<dev.dshmobile.app.model.SessionSummary>()
        val ids = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(10) {
            val url = request("sessions").build().url.newBuilder().addQueryParameter("limit", "100")
                .apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
            val page = decodeWire<SessionResponse>(execute(request("sessions").url(url).build(), SNAPSHOT_LIMIT).checked())
            if (page.items.size > 100 || page.items.any { !ids.add(it.id) }) throw MobileFailure("invalid_response")
            page.items.forEach { it.checked() }
            items += page.items
            val next = page.nextCursor ?: return SessionIndex(items, false)
            if (next.isBlank() || next.length > 4096 || next.any { it.isISOControl() } || !cursors.add(next) || page.items.isEmpty()) throw MobileFailure("invalid_response")
            cursor = next
        }
        return SessionIndex(items, true)
    }
    suspend fun snapshot(sessionId: String): SessionSnapshot = try {
        val result = execute(request("sessions", sessionId).build(), SNAPSHOT_LIMIT)
        // Up to 2 MiB: decoding/validation must not resume on the UI dispatcher.
        withContext(Dispatchers.Default) { decodeWire<SessionSnapshot>(result.checked(history = true)).checked(sessionId) }
    } catch (failure: MobileFailure) {
        // Receiving a bad/oversized history does not prove the paired host is offline.
        if (failure.key == "invalid_response") throw MobileFailure("chat_unavailable")
        throw failure
    }

    suspend fun command(value: StoredCommand): CommandReceipt {
        val builder: Request.Builder
        val body: String
        when (value.kind) {
            "create" -> { builder = request("sessions"); body = mobileJson.encodeToString(CreateBody(value.requestId, requireNotNull(value.workspaceId), value.presetId)) }
            "send" -> { builder = request("sessions", requireNotNull(value.sessionId), "messages"); body = mobileJson.encodeToString(MessageBody(value.requestId, requireNotNull(value.text), value.delivery, value.uploadIds)) }
            "cancel" -> { builder = request("sessions", requireNotNull(value.sessionId), "cancellations"); body = mobileJson.encodeToString(CancelBody(value.requestId, requireNotNull(value.expectedCursor))) }
            "queue" -> { builder = request("sessions", requireNotNull(value.sessionId), "queue"); body = mobileJson.encodeToString(QueueBody(value.requestId, requireNotNull(value.itemId), requireNotNull(value.action))) }
            "rename" -> { builder = request("sessions", requireNotNull(value.sessionId), "title"); body = mobileJson.encodeToString(TitleBody(value.requestId, requireNotNull(value.title))) }
            "model" -> { builder = request("sessions", requireNotNull(value.sessionId), "model"); val s = requireNotNull(value.selection); body = mobileJson.encodeToString(ModelBody(value.requestId, s.provider, s.model, s.reasoningEffort)) }
            "answer" -> { builder = request("sessions", requireNotNull(value.sessionId), "answers"); body = if (value.skip) mobileJson.encodeToString(SkipBody(value.requestId, requireNotNull(value.callId), true)) else mobileJson.encodeToString(AnswerBody(value.requestId, requireNotNull(value.callId), requireNotNull(value.answer))) }
            else -> throw MobileFailure("invalid_selection")
        }
        if (body.toByteArray().size > 64 * 1024) throw MobileFailure("invalid_text")
        val result = execute(builder.post(body.toRequestBody(jsonType)).build())
        // Known rejections return a durable receipt at a 4xx status, unlike pre-admission failures.
        if (result.status in 200..299 || result.text.let { runCatching { mobileJson.parseToJsonElement(it).let { element -> element is kotlinx.serialization.json.JsonObject && "requestId" in element } }.getOrDefault(false) }) {
            return decodeWire<CommandReceipt>(result.text).checked(value.requestId).also {
                if (result.status !in 200..299 && it.status != "rejected") throw MobileFailure("invalid_response")
            }
        }
        if (value.kind == "send" && !value.uploadIds.isNullOrEmpty() && result.status == 404 &&
            runCatching { decodeWire<ErrorEnvelope>(result.text).error.code == "not_found" }.getOrDefault(false)) {
            // Host resolves staging before admission; no receipt exists for this rejected payload.
            throw MobileFailure("upload_missing")
        }
        throw result.errorFailure()
    }
    suspend fun models(): dev.dshmobile.app.model.ModelCatalog = decodeWire<dev.dshmobile.app.model.ModelCatalog>(execute(request("models").build(), SNAPSHOT_LIMIT).checked()).also { catalog ->
        if (catalog.items.size > 1000 || catalog.items.any { !validId(it.provider) || !validId(it.id) || it.name.length > 1024 }) throw MobileFailure("invalid_response")
    }

    suspend fun discardUpload(sessionId: String, uploadId: String) {
        val result = execute(request("sessions", sessionId, "uploads", uploadId).delete().build())
        if (result.status !in setOf(204, 404)) result.checked()
    }

    suspend fun upload(sessionId: String, name: String, mime: String, size: Long, open: () -> java.io.InputStream, progress: (Int) -> Unit): UploadReceipt {
        if (size !in 1..dev.dshmobile.app.model.PhoneRules.FILE_LIMIT) throw MobileFailure("invalid_selection")
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(32 * 1024); var sent = 0L
                    while (sent < size) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                        if (count < 0) throw IOException("short_upload")
                        sink.write(buffer, 0, count); sent += count; progress((sent * 100 / size).toInt())
                    }
                    if (input.read() != -1) throw IOException("long_upload")
                }
            }
        }
        val result = execute(request("sessions", sessionId, "uploads").header("X-File-Name", java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")).post(body).build())
        if (result.status != 201) { result.checked(); throw MobileFailure("invalid_response") }
        return decodeWire<UploadReceipt>(result.text).also {
            if (!validId(it.uploadId) || it.expiresAt < 0 || it.attachment.size != size || it.attachment.mime != mime) throw MobileFailure("invalid_response")
        }
    }

    /** Memory-only bounded response; no HTTP disk cache contains private chat images. */
    suspend fun image(sessionId: String, attachmentId: String): ByteArray = binary(sessionId, attachmentId, false)
    suspend fun file(sessionId: String, attachmentId: String): ByteArray = binary(sessionId, attachmentId, true)
    private suspend fun binary(sessionId: String, attachmentId: String, file: Boolean): ByteArray = suspendCancellableCoroutine { continuation ->
        synchronized(admission) {
            if (retired.get()) { continuation.resumeWithException(MobileFailure("network_unavailable")); return@suspendCancellableCoroutine }
            val call = client.newCall(request("sessions", sessionId, if (file) "files" else "attachments", attachmentId).header("Accept", if (file) "application/octet-stream" else "image/*").build())
            continuation.invokeOnCancellation { cancelOnIo { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(MobileFailure("network_unavailable")) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (it.code !in 200..299 || (if (file) it.body?.contentType()?.toString() != "application/octet-stream" else it.body?.contentType()?.toString() !in dev.dshmobile.app.model.PhoneRules.imageMimes)) throw MobileFailure("chat_unavailable")
                            val body = it.body ?: throw MobileFailure("invalid_response")
                            if (body.contentLength() > dev.dshmobile.app.model.PhoneRules.FILE_LIMIT) throw MobileFailure("invalid_response")
                            body.byteStream().use { input -> input.readAtMost(dev.dshmobile.app.model.PhoneRules.FILE_LIMIT.toInt() + 1) }.also { data ->
                                if (data.size > dev.dshmobile.app.model.PhoneRules.FILE_LIMIT) throw MobileFailure("invalid_response")
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(MobileFailure("chat_unavailable")) }
                }
            })
        }
    }

    suspend fun receipt(requestId: String): CommandReceipt? {
        val result = execute(request("commands", requestId).build())
        if (result.status == 404) { decodeWire<ErrorEnvelope>(result.text); return null }
        return decodeWire<CommandReceipt>(result.checked()).checked(requestId)
    }

    fun observe(sessionId: String, onSnapshot: (SessionSnapshot) -> Unit, onFailure: (MobileFailure) -> Unit): EventSource {
        val request = request("sessions", sessionId, "events").header("Accept", "text/event-stream").build()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val source = synchronized(admission) {
            if (retired.get()) throw MobileFailure("network_unavailable")
            EventSources.createFactory(eventClient).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (retired.get() || cancelled.get() || type != "snapshot") return // No unversioned event is interpreted as a state patch.
                try {
                    if (data.toByteArray().size > SNAPSHOT_LIMIT) throw MobileFailure("invalid_response")
                    onSnapshot(decodeWire<SessionSnapshot>(data).checked(sessionId))
                } catch (failure: MobileFailure) {
                    // Terminal decode failure owns cancellation synchronously: later buffered
                    // frames or the physical close must not reclassify it as a host outage.
                    if (cancelled.compareAndSet(false, true)) {
                        cancelOnIo { eventSource.cancel() }
                        onFailure(if (failure.key == "invalid_response") MobileFailure("chat_unavailable") else failure)
                    }
                }
            }
            override fun onClosed(eventSource: EventSource) {
                if (!retired.get() && !cancelled.get()) onFailure(MobileFailure("network_unavailable"))
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (retired.get() || cancelled.get()) return
                val status = response?.code
                onFailure(MobileFailure(when (status) {
                    401 -> "unauthorized"
                    403 -> "forbidden"
                    429 -> "rate_limited"
                    in 400..599 -> "chat_unavailable"
                    else -> when (t) {
                        is EventLimitExceeded -> "chat_unavailable"
                        is SSLException -> "tls_failed"
                        else -> "network_unavailable"
                    }
                }, retryable = status == 429 || (status != null && status in 500..599)))
            }
            })
        }
        return object : EventSource {
            override fun request(): Request = request
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) cancelOnIo { source.cancel() }
            }
        }
    }
    suspend fun notificationSettings(): NotificationSettings = decodeWire<NotificationSettings>(execute(request("notification-settings").build()).checked())
    suspend fun putNotificationSettings(value: NotificationSettings): NotificationSettings = decodeWire<NotificationSettings>(execute(request("notification-settings")
        .put(mobileJson.encodeToString(NotificationSettingsBody(value.revision, value.enabled, value.projects, value.chats)).toRequestBody(jsonType)).build()).checked())

    fun observeNotifications(after: String?, onPage: (NotificationPage) -> Unit, onFailure: (String, Long) -> Unit): EventSource {
        val builder = request("notification-events", "stream")
        val url = builder.build().url.newBuilder().apply { after?.let { addQueryParameter("after", it) } }.build()
        val req = builder.url(url).header("Accept", "text/event-stream").build()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val source = synchronized(admission) {
            if (retired.get()) throw MobileFailure("network_unavailable")
            EventSources.createFactory(notificationClient).newEventSource(req, object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (cancelled.get() || retired.get() || type != "notification-page") return
                    try {
                        if (data.toByteArray().size > 128 * 1024) throw MobileFailure("invalid_response")
                        onPage(decodeWire<NotificationPage>(data).checked())
                    } catch (_: Exception) { cancelOnIo { eventSource.cancel() }; onFailure("invalid_response", 0) }
                }
                override fun onClosed(eventSource: EventSource) { if (!cancelled.get() && !retired.get()) onFailure("network_unavailable", 0) }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (cancelled.get() || retired.get()) return
                    val retry = retryAfterMillis(response?.header("Retry-After"))
                    onFailure(when { response?.code == 401 -> "unauthorized"; response?.code == 403 -> "forbidden"; response?.code == 429 -> "rate_limited"; t is SSLException -> "tls_failed"; t is EventLimitExceeded -> "invalid_response"; else -> "network_unavailable" }, retry)
                }
            })
        }
        return object : EventSource {
            override fun request() = req
            override fun cancel() { if (cancelled.compareAndSet(false, true)) cancelOnIo { source.cancel() } }
        }
    }

    private fun cancelOnIo(cancel: () -> Unit) {
        if (!io.submit(cancel) && !retired.get()) close()
    }
    /** Logical retirement is immediate; no physical TLS/socket IO occurs on the caller thread. */
    fun close() {
        synchronized(admission) { if (!retired.compareAndSet(false, true)) return }
        // Never hold request admission while visiting proxy/tunnel state or performing physical IO.
        relayProxy?.retire()
        io.close {
            try {
                relayProxy?.closePhysical() // Break tunnels before inner TLS close-notify/cancel.
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }
    suspend fun awaitClosed() = io.awaitClosed()
    suspend fun closeAndAwait() { close(); awaitClosed() }

    internal data class SessionIndex(val items: List<dev.dshmobile.app.model.SessionSummary>, val truncated: Boolean)
    private data class HttpResult(val status: Int, val text: String, val retryAfterMs: Long = 0)
    /** Internal category only: never retain the raw exception, its message, cause or request. */
    private class ReadConnectionClosed(val call: Call) : Exception("network_unavailable")
    private companion object { const val SNAPSHOT_LIMIT = 2 * 1024 * 1024 }
}
internal fun safeToken(value: String): Boolean = value.length in 16..512 && value.all { it.code in 33..126 }
