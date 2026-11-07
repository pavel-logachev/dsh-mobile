package dev.dshmobile.app.data

import android.content.Context
import dev.dshmobile.app.BuildConfig
import dev.dshmobile.app.model.ConnectionState
import dev.dshmobile.app.model.MobileReducer
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.model.SessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.sse.EventSource
import java.util.UUID
import kotlin.random.Random

fun createMobileRepository(context: Context, scope: CoroutineScope): MobileRepository =
    NetworkMobileRepository(EncryptedStateStore(context.applicationContext), scope, BuildConfig.DEBUG, observeRestoredSession = false,
        historyStore = EncryptedHistoryStore(context.applicationContext), prefetchEnabled = true,
        networkWake = repositoryNetworkWake(context.applicationContext),
        watchPrompt = { device, session, request, title -> registerPhoneWatch(context.applicationContext, device, session, request, title) },
        unwatchPrompt = { request -> NotificationStore(context.applicationContext).update { it.copy(watches = it.watches.filterNot { w -> w.requestId == request }) }; Unit },
        openAttachment = { source -> context.contentResolver.openInputStream(android.net.Uri.parse(source.uri)) ?: throw java.io.IOException("unavailable") })

/** One process owner prevents Activity recreation from restoring an in-flight command as unknown
 * or racing two encrypted-state writers. Process death still recovers by receipt, never replay. */
internal object ApplicationMobileRepository {
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + SupervisorJob())
    private var repository: MobileRepository? = null
    @Synchronized fun get(context: Context): MobileRepository = repository ?: createMobileRepository(context.applicationContext, scope).also { repository = it }
}

/**
 * The single admission lock covers persistence, dispatch and receipt recovery. Lifecycle changes
 * close observation synchronously, independent of a slow mutation. They NEVER cancel a host turn.
 */
internal class NetworkMobileRepository(
    private val store: SecureStateStore,
    ownerScope: CoroutineScope,
    private val debug: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelay: (Int) -> Long = { attempt ->
        val cap = minOf(30_000L, 1_000L shl minOf(attempt, 5))
        Random.nextLong(cap / 2, cap + 1)
    },
    private val observeRestoredSession: Boolean = true,
    private val historyStore: HistoryStore = MemoryHistoryStore(),
    private val prefetchEnabled: Boolean = false,
    private val preHeaderRetryDelay: (Int) -> Long = { attempt -> if (attempt == 0) 500L else 1_500L },
    private val openAttachment: ((dev.dshmobile.app.model.DraftAttachment) -> java.io.InputStream)? = null,
    private val watchPrompt: suspend (String, String, String, String) -> Unit = { _, _, _, _ -> },
    private val unwatchPrompt: suspend (String) -> Unit = {},
    private val networkWake: (((() -> Unit)) -> (() -> Unit))? = null,
    private val firstSnapshotTimeoutMs: Long = 15_000L,
    private val indexRefreshIntervalMs: Long = 5_000L,
) : MobileRepository {
    private val repositoryJob = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + repositoryJob)
    private val mutex = Mutex()
    private val fileReads = kotlinx.coroutines.sync.Semaphore(2)
    private val history = SessionHistoryCache(now)
    private val diskHistory = PersistentHistory(historyStore, now, flushScope = scope)
    @Volatile private var historyEpoch = historyStore.authority.epoch
    private var removeHistoryListener: (() -> Unit)? = null
    private val cacheMutex = Mutex()
    private val prefetch = HistoryPrefetch(scope, now, read = { api().snapshot(it) }, publish = { snapshot ->
        action {
            if (foreground && state.value.connection == ConnectionState.ONLINE && state.value.sessions.any {
                    it.id == snapshot.session.id && it.workspaceId == snapshot.session.workspaceId }) cacheSnapshot(snapshot)
        }
    }, terminal = { key -> scope.launch { action { fail(key) } } })
    private suspend fun cacheSnapshot(snapshot: SessionSnapshot) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        cacheMutex.withLock {
            checkHistoryEpoch()
            stored.host?.let { diskHistory.bindAuthorized(historyBinding(it), historyEpoch) }
            history.put(snapshot)
            try { diskHistory.put(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional cache failure never changes receipt semantics. */ }
        }
    }
    private val selectionGuard = Any()
    private var selection: Deferred<Unit>? = null
    private var historyRead: Deferred<SessionSnapshot>? = null
    @Volatile private var selectionRevision = 0L
    @Volatile private var requestedSessionId: String? = null
    @Volatile private var observingSession = observeRestoredSession
    // An admitted command outlives the local bubble/composition that initiated it.
    private val promptActions = dev.dshmobile.app.model.LocalPromptActions(
        dismiss = { id -> scope.launch { dismissLocalPrompt(id) }.join() },
        resend = { id -> scope.launch { resendLocalPrompt(id) }.join() },
    )
    private val mutableState = MutableStateFlow(MobileState(localPromptActions = promptActions, observingSession = observingSession))
    override val state: StateFlow<MobileState> = mutableState.asStateFlow()
    private val mutableAcceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
    override val acceptedCreate: StateFlow<CreateAcceptance?> = mutableAcceptedCreate.asStateFlow()
    private var stored = StoredState()
    private var restored = false
    @Volatile private var api: HostApi? = null
    @Volatile private var pairingTransport: HostApi? = null
    @Volatile private var foreground = false
    @Volatile private var desiredForeground = false
    private var foregroundRevision = 0L
    private val lifecycle = Any()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var observer: EventSource? = null
    private var snapshotIngress: SnapshotIngress<SessionSnapshot>? = null
    private var firstSnapshotRead: kotlinx.coroutines.CompletableDeferred<SessionSnapshot>? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var networkWakeJob: Job? = null
    private var unregisterNetworkWake: (() -> Unit)? = null

    /** Read-only wake; flaps coalesce and never cancel or replay an admitted command. */
    fun networkRestored() {
        synchronized(lifecycle) {
            if (!foreground || !desiredForeground || closed || state.value.connection != ConnectionState.OFFLINE || networkWakeJob?.isActive == true) return
            networkWakeJob = scope.launch(start = CoroutineStart.LAZY) {
                cancelReconnect()
                reconnectAttempt = 0
                action {
                    if (foreground && desiredForeground && state.value.connection == ConnectionState.OFFLINE && stored.host != null)
                        refreshLocked()
                }
            }.also { it.start() }
        }
    }
    private var projectionRetryJob: Job? = null
    private var projectionRetryAttempt = 0
    // Connectivity is visible before the fresh metadata/history cut authorizes actions.
    private var syncing = false
    private var indexJob: Job? = null
    private var listRefreshJob: Job? = null
    private fun stopListRefresh() { listRefreshJob?.cancel(); listRefreshJob = null }
    /** Only the foreground home route polls the roster. No transcript reads or command replay. */
    private fun startListRefresh() {
        if (listRefreshJob?.isActive == true || !foreground || !desiredForeground || observingSession || closed) return
        listRefreshJob = scope.launch {
            while (true) {
                delay(indexRefreshIntervalMs)
                action {
                    if (!foreground || !desiredForeground || observingSession || closed ||
                        state.value.connection != ConnectionState.ONLINE || indexJob?.isActive == true) return@action
                    val network = api()
                    val first = network.sessions(firstPageOnly = true)
                    checkHistoryEpoch()
                    if (first.items.any { item -> state.value.workspaces.none { it.id == item.workspaceId } }) throw MobileFailure("invalid_response")
                    cancelIndex()
                    val index = mergeIndex(first)
                    saveIndex(state.value.workspaces, index)
                    mutableState.value = state.value.copy(sessions = index.items, sessionsTruncated = index.truncated, lastSyncedAt = now())
                    loadRemainingIndex(network, first)
                }
            }
        }
    }
    @Volatile private var indexRevision = 0L
    // Point reads / admitted mutations are newer evidence than an already-started page chain.
    private var sessionRevision = 0L
    private fun mergeIndex(index: HostApi.SessionIndex, preserveCurrent: Boolean = index.truncated, preferCurrent: Boolean = false): HostApi.SessionIndex {
        if (!preserveCurrent) return index
        val authorized = state.value.sessions.filter { session -> state.value.workspaces.any { it.id == session.workspaceId } }
        val items = (if (preferCurrent) authorized + index.items else index.items + authorized).distinctBy { it.id }
        return index.copy(items = items.take(1000), truncated = index.truncated || items.size > 1000)
    }
    private fun cancelIndex() { indexRevision++; indexJob?.cancel(); indexJob = null }
    private suspend fun saveIndex(workspaces: List<dev.dshmobile.app.model.Workspace>, index: HostApi.SessionIndex) {
        if (!index.truncated) history.retain(index.items.map { it.id }.toSet())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cacheMutex.withLock {
                try {
                    checkHistoryEpoch()
                    stored.host?.let { diskHistory.bindAuthorized(historyBinding(it), historyEpoch) }
                    if (!index.truncated) diskHistory.retain(index.items.map { it.id }.toSet())
                    diskHistory.updateIndex(HistoryIndex(workspaces, index.items, index.truncated, now()))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: MobileFailure) { throw failure }
                catch (_: Exception) { /* Optional cache cannot gate authenticated reads. */ }
            }
        }
    }
    private fun loadRemainingIndex(network: HostApi, first: HostApi.SessionIndex) {
        if (first.nextCursor == null || first.pages >= 10) return
        val revision = indexRevision
        val evidenceRevision = sessionRevision
        indexJob = scope.launch {
            try {
                val result = network.sessions(after = first)
                action {
                    if (revision != indexRevision || !foreground || !desiredForeground || api !== network) return@action
                    checkHistoryEpoch()
                    if (result.items.any { item -> state.value.workspaces.none { it.id == item.workspaceId } }) throw MobileFailure("invalid_response")
                    val complete = mergeIndex(result, result.truncated || evidenceRevision != sessionRevision, preferCurrent = evidenceRevision != sessionRevision)
                    saveIndex(state.value.workspaces, complete)
                    if (!complete.truncated && stored.selectedSessionId?.let { id -> complete.items.none { it.id == id } } == true) {
                        stopObserver()
                        persist(stored.copy(selectedSessionId = null))
                        mutableState.value = state.value.copy(snapshot = null, snapshotStale = true, draft = "")
                    }
                    mutableState.value = state.value.copy(sessions = complete.items, sessionsTruncated = complete.truncated)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                action { if (revision == indexRevision && foreground && api === network) fail(if (failure is MobileFailure) failure.key else "invalid_response") }
            }
        }
    }
    private data class DraftEdit(val revision: Long, val text: String)
    private val draftSequence = java.util.concurrent.atomic.AtomicLong()
    private val liveDrafts = java.util.concurrent.ConcurrentHashMap<String, DraftEdit>()
    private fun draftFor(sessionId: String?): String = sessionId?.let { liveDrafts[it]?.text ?: stored.drafts[it] }.orEmpty()
    init {
        removeHistoryListener = historyStore.authority.listen { key ->
            history.clear(); diskHistory.invalidateMemory(); prefetch.cancel(); stopObserver(); cancelReconnect(); cancelIndex()
            api?.close(); api = null
            hideRejectedHistory(key)
        }
    }
    private fun hideRejectedHistory(key: String) {
        mutableState.value = state.value.copy(connection = ConnectionState.REVOKED, busy = false, error = key,
            sessions = emptyList(), workspaces = emptyList(), snapshot = null, snapshotStale = true)
    }
    private fun checkHistoryEpoch() {
        if (historyEpoch != historyStore.authority.epoch) { hideRejectedHistory("revoked"); throw MobileFailure("revoked") }
    }

    private suspend fun action(block: suspend () -> Unit) {
        mutex.withLock {
            if (closed) return
            try { block(); checkHistoryEpoch() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                fail(if (failure is MobileFailure) failure.key else "storage_failed", failure is MobileFailure && failure.retryable)
            } finally {
                if (historyEpoch != historyStore.authority.epoch) hideRejectedHistory("revoked")
            }
        }
    }
    private suspend fun fail(key: String, retryable: Boolean = false) {
        val connection = when (key) {
            "revoked", "unauthorized" -> ConnectionState.REVOKED
            "incompatible_protocol" -> ConnectionState.INCOMPATIBLE
            "network_unavailable", "tls_failed", "invalid_response", "host_error", "rate_limited" -> ConnectionState.OFFLINE
            else -> state.value.connection
        }
        prefetch.cancel()
        if (key in setOf("revoked", "unauthorized", "forbidden")) {
            if (historyEpoch == historyStore.authority.epoch) {
                try { historyStore.authority.invalidate(key) { historyStore.clear() } }
                catch (_: Exception) { /* Rejection already invalidated every memory owner. */ }
            }
            history.clear(); diskHistory.invalidateMemory()
        }
        mutableState.value = state.value.copy(connection = connection, busy = false, error = key,
            sessions = if (key in setOf("revoked", "unauthorized", "forbidden")) emptyList() else state.value.sessions,
            workspaces = if (key in setOf("revoked", "unauthorized", "forbidden")) emptyList() else state.value.workspaces,
            snapshot = if (key in setOf("revoked", "unauthorized", "forbidden")) null else state.value.snapshot,
            snapshotStale = state.value.snapshotStale || connection != ConnectionState.ONLINE || key == "chat_unavailable")
        if (connection != ConnectionState.ONLINE) cancelIndex()
        if (connection != ConnectionState.ONLINE || key in setOf("forbidden", "chat_unavailable")) stopObserver()
        if (connection == ConnectionState.OFFLINE) scheduleReconnect()
        if (key == "chat_unavailable" && retryable) scheduleProjectionRetry()
    }
    private fun protectUploads(value: StoredState): StoredState {
        val instant = now()
        val used = (stored.possiblyUsedUploads + value.possiblyUsedUploads).filterValues { it > instant }.toMutableMap()
        // Include the previous records before removing them, including abandonment/receipt reconciliation.
        (stored.acceptedPrompts + listOfNotNull(stored.pending) + value.acceptedPrompts + listOfNotNull(value.pending))
            .filter { it.kind == "send" }.flatMap { it.uploadIds.orEmpty() }.forEach { used.putIfAbsent(it, instant + 600_000) }
        val overflow = used.size > 1280
        return value.copy(possiblyUsedUploads = used.entries.toList().takeLast(1280).associate { it.toPair() },
            uploadProtectionUntil = maxOf(stored.uploadProtectionUntil, value.uploadProtectionUntil, if (overflow) instant + 600_000 else 0))
    }
    private suspend fun persist(input: StoredState) {
        val value = protectUploads(input)
        try { store.write(value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { safeDebugDiagnostic("state.persist", failure); throw MobileFailure("storage_failed") }
        stored = value
        mutableState.value = state.value.copy(pending = value.pending?.presentation(), sendNotSent = value.selectedSessionId in value.notSentSessions, acceptedPrompts = value.acceptedPrompts.map { it.presentation() },
            attachmentDrafts = value.attachmentDrafts, answerDrafts = value.answerDrafts,
            retainedAttachmentSources = value.acceptedPrompts.flatMap { it.attachmentSources } + value.pending?.attachmentSources.orEmpty())
    }
    private fun stopObserver() {
        firstSnapshotRead?.cancel(); firstSnapshotRead = null
        generation++
        snapshotIngress?.close()
        snapshotIngress = null
        observer?.cancel()
        observer = null
    }
    private fun cancelReconnect() { reconnectJob?.cancel(); reconnectJob = null }
    private fun safeRelayOrigin(relay: RelaySettings): String {
        val uri = java.net.URI(relay.url)
        return uri.host + if (uri.port >= 0) ":${uri.port}" else ""
    }
    private fun api(): HostApi = synchronized(lifecycle) {
        if (!foreground || !desiredForeground || closed) throw MobileFailure("network_unavailable")
        // Cleanup overflow can retire a transport independently of Activity lifecycle. Never
        // keep handing that terminal instance to manual, backoff or restored-network reads.
        api?.takeUnless { it.isRetired } ?: run {
            val host = stored.host ?: throw MobileFailure("not_paired")
            HostApi(host.endpoint, host.deviceToken).also { api = it }
        }
    }
    private fun online() {
        if (stored.host == null) throw MobileFailure("not_paired")
        if (!foreground || syncing || state.value.connection != ConnectionState.ONLINE) throw MobileFailure("offline_no_send")
        if (stored.pending != null) throw MobileFailure("command_unresolved")
    }
    private suspend fun restoreLocked() {
        if (restored) return
        stored = try { store.read() } catch (_: Exception) { throw MobileFailure("storage_failed") }
        val host = stored.host
        if (host != null) {
            // Even expired trust material is a paired LOCAL record. Publish only safe metadata so
            // the owner can forget/reset; never send credentials until validation succeeds.
            mutableState.value = state.value.copy(paired = true, hostName = host.hostName.take(512),
                remoteMode = host.endpoint.relay != null, connection = ConnectionState.OFFLINE)
            val normalized = EndpointPolicy.validate(host.endpoint, debug)
            if (normalized != host.endpoint || !validId(host.deviceId) || !safeToken(host.deviceToken) || host.hostName.length > 512) throw MobileFailure("storage_failed")
            stored.pending?.let { validateStoredCommand(it) }
            if (stored.acceptedPrompts.count { it.status == "unconfirmed" } > MAX_LOCAL_PROMPTS ||
                stored.acceptedPrompts.filter { it.status in setOf("accepted", "queued") }.groupingBy { it.sessionId }.eachCount().values.any { it > MobileReducer.MAX_ACCEPTED_PROMPTS } ||
                stored.acceptedPrompts.map { it.requestId }.distinct().size != stored.acceptedPrompts.size ||
                stored.acceptedPrompts.any { it.requestId == stored.pending?.requestId || it.kind != "send" || it.status !in setOf("accepted", "queued", "unconfirmed") })
                throw MobileFailure("storage_failed")
            stored.acceptedPrompts.forEach { validateStoredCommand(it) }
            val sanitized = stored.sanitizeDrafts()
            if (sanitized != stored) {
                val oldDrafts = stored.attachmentDrafts
                store.write(sanitized); stored = sanitized
                oldDrafts.filterKeys(::validId).forEach { (session, sources) ->
                    discardStaging(session, sources.filter { it.uploadId?.let(::validId) == true })
                }
            }
            synchronized(lifecycle) {
                if (foreground && desiredForeground && !closed) api = HostApi(host.endpoint, host.deviceToken)
            }
        } else if (stored.pending != null || stored.acceptedPrompts.isNotEmpty() || stored.drafts.isNotEmpty() || stored.selectedSessionId != null || stored.attachmentDrafts.isNotEmpty() || stored.answerDrafts.isNotEmpty()) {
            throw MobileFailure("storage_failed")
        }
        val lastIndex = if (host != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cacheMutex.withLock { diskHistory.restore(historyBinding(host)); diskHistory.index() }
        } else null
        restored = true
        if (stored.pending?.status == "sending" || stored.pending?.status == "pending") {
            // A previous process could have dispatched. Only receipt/canonical reads may resolve it.
            persist(stored.copy(pending = stored.pending?.copy(status = "uncertain")))
        }
        mutableState.value = MobileState(
            manualRefreshing = state.value.manualRefreshing,
            paired = host != null, hostName = host?.hostName.orEmpty(),
            remoteMode = host?.endpoint?.relay != null, relayHost = host?.endpoint?.relay?.let { safeRelayOrigin(it) },
            connection = if (host == null) ConnectionState.DISCONNECTED else ConnectionState.OFFLINE,
            sessions = lastIndex?.sessions.orEmpty(), workspaces = lastIndex?.workspaces.orEmpty(),
            sessionsTruncated = lastIndex?.truncated ?: false,
            draft = draftFor(stored.selectedSessionId), pending = stored.pending?.presentation(), sendNotSent = stored.selectedSessionId in stored.notSentSessions,
            acceptedPrompts = stored.acceptedPrompts.map { it.presentation() }, localPromptActions = promptActions,
            error = if (stored.pending != null) "command_uncertain" else null, observingSession = observingSession,
            draftsRestored = true, attachmentDrafts = stored.attachmentDrafts, answerDrafts = stored.answerDrafts,
            retainedAttachmentSources = stored.acceptedPrompts.flatMap { it.attachmentSources } + stored.pending?.attachmentSources.orEmpty(),
        )
        if (lastIndex != null) StartupPerf.mark("cache_list_shown")
    }
    override suspend fun restore() = action {
        restoreLocked()
        if (foreground && stored.host != null) refreshLocked()
    }
    override suspend fun pair(invitationJson: String, deviceName: String) = action {
        pairLocked(Invitation.parse(InvitationQrPayload.decode(invitationJson), debug, now()), deviceName)
    }
    override suspend fun pair(invitation: Invitation, deviceName: String) = action {
        pairLocked(invitation, deviceName)
    }
    private suspend fun pairLocked(invitation: Invitation, deviceName: String) {
        restoreLocked()
        if (stored.host != null) throw MobileFailure("command_unresolved")
        // Recheck time/transport at dispatch without reparsing or substituting the reviewed object.
        EndpointPolicy.validate(invitation.endpoint, debug)
        if (!safeToken(invitation.pairingToken) || invitation.endpoint.relay?.expiresAt?.let { it <= now() } == true)
            throw MobileFailure("invitation_invalid")
        val name = deviceName.trim()
        if (name.isBlank() || name.length > 80 || name.any { it.isISOControl() }) throw MobileFailure("invitation_invalid")
        mutableState.value = state.value.copy(connection = ConnectionState.CONNECTING, busy = true, error = null)
        val pairingApi = synchronized(lifecycle) {
            if (!foreground || !desiredForeground || closed) throw MobileFailure("network_unavailable")
            HostApi(invitation.endpoint).also { pairingTransport = it }
        }
        var checkpoint = "pair.request"
        try {
            val response = pairingApi.pair(invitation.pairingToken, name)
            checkpoint = "pair.validate-device-access"
            val pairedEndpoint = invitation.endpoint.relay?.let { bootstrap ->
                val access = RelayPolicy.checkedAccess(response.relayAccess ?: throw MobileFailure("invalid_response"), now())
                if (access.accessId == bootstrap.accessId || access.accessToken == bootstrap.accessToken) throw MobileFailure("invalid_response")
                invitation.endpoint.copy(relay = bootstrap.withAccess(access))
            } ?: invitation.endpoint
            val host = PairedHost(pairedEndpoint, response.deviceId, response.deviceToken, response.hostName)
            // Token is persisted encrypted before UI considers it paired. Pair secret is NEVER stored.
            checkpoint = "pair.persist-device-access"
            historyEpoch = historyStore.authority.epoch
            prefetch.reset(); history.clear()
            cacheMutex.withLock { diskHistory.clear(); diskHistory.restore(historyBinding(host)) }
            persist(StoredState(host = host))
            checkpoint = "pair.close-bootstrap"
            pairingApi.closeAndAwait() // Suspend outside lifecycle monitor; no bootstrap overlap.
            checkpoint = "pair.create-device-transport"
            synchronized(lifecycle) {
                if (foreground && desiredForeground && !closed) api = HostApi(host.endpoint, host.deviceToken)
            }
            mutableState.value = MobileState(paired = true, draftsRestored = true, hostName = host.hostName, localPromptActions = promptActions,
                remoteMode = host.endpoint.relay != null, relayHost = host.endpoint.relay?.let { safeRelayOrigin(it) },
                connection = ConnectionState.SYNCING, busy = true, observingSession = observingSession)
            checkpoint = "pair.device-refresh"
            if (foreground) refreshLocked() else mutableState.value = state.value.copy(connection = ConnectionState.OFFLINE, busy = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { safeDebugDiagnostic(checkpoint, failure); throw failure }
        finally { pairingTransport = null; pairingApi.close() }
    }
    override suspend fun refresh() = action {
        mutableState.value = state.value.copy(manualRefreshing = true)
        try {
            restoreLocked()
            if (stored.host == null) throw MobileFailure("not_paired")
            if (foreground) refreshLocked()
        } finally { mutableState.value = state.value.copy(manualRefreshing = false) }
    }
    private data class RefreshMetadata(
        val capabilities: CapabilitiesResponse,
        val workspaces: List<dev.dshmobile.app.model.Workspace>,
        val presets: List<dev.dshmobile.app.model.Preset>,
        val index: HostApi.SessionIndex,
    )
    private suspend fun refreshLocked(readHistory: Boolean = true) {
        try {
            val target = requestedSessionId?.takeIf { observingSession && readHistory }
            val revision = selectionRevision
            refreshFromHostLocked(readHistory && target == null)
            if (target != null && revision == selectionRevision && observingSession)
                selectLocked(target, revision, metadataReady = true)
        }
        catch (cancelled: CancellationException) {
            // A navigation-owned refresh can be cancelled during metadata GETs. The closed stream
            // and incomplete handshake are not ONLINE, and SYNCING must not survive its owner.
            if (syncing) {
                mutableState.value = state.value.copy(connection = ConnectionState.OFFLINE, busy = false,
                    snapshotStale = state.value.snapshot != null)
                scheduleReconnect()
            }
            throw cancelled
        } finally { syncing = false }
    }
    private suspend fun refreshFromHostLocked(readHistory: Boolean) {
        historyEpoch = historyStore.authority.epoch
        val network = api()
        cancelIndex()
        prefetch.cancel()
        stopObserver()
        syncing = true
        mutableState.value = state.value.copy(connection = ConnectionState.SYNCING, busy = true, error = null,
            snapshotStale = state.value.snapshot != null)
        // Speculate only a read. Unsupported hosts may reject it; discard both result and failure.
        val metadata = coroutineScope {
            val caps = async {
                network.capabilities().also { checkHistoryEpoch() }
            }
            val workspaces = async { network.workspaces() }
            val presets = async { network.presets() }
            val index = async {
                try { Result.success(network.sessions(firstPageOnly = true)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            }
            val negotiated = caps.await()
            val sessions = if (negotiated.capabilities.sessions) index.await().getOrThrow()
                else { index.cancel(); HostApi.SessionIndex(emptyList(), false) }
            RefreshMetadata(negotiated, workspaces.await(), presets.await(), sessions)
        }
        checkHistoryEpoch()
        val (caps, workspaces, presets, receivedIndex) = metadata
        if (receivedIndex.items.any { session -> workspaces.none { it.id == session.workspaceId } }) throw MobileFailure("invalid_response")
        val index = if (receivedIndex.truncated) mergeIndex(receivedIndex).let { merged ->
            merged.copy(items = merged.items.filter { session -> workspaces.any { it.id == session.workspaceId } })
        } else receivedIndex
        val sessions = index.items
        if (sessions.any { session -> workspaces.none { it.id == session.workspaceId } }) throw MobileFailure("invalid_response")
        // Absence in the first page is not revocation or permission to erase cached history.
        val selected = stored.selectedSessionId?.takeIf { id -> index.truncated || sessions.any { it.id == id } }
        // Publish the authorized index before any potentially slow history projection.
        mutableState.value = state.value.copy(hostName = caps.hostName, capabilities = caps.capabilities,
            workspaces = workspaces, presets = presets, sessions = sessions, sessionsTruncated = index.truncated,
            demo = caps.upstreamVersion == "fixture", lastSyncedAt = now(),
            connection = if (foreground) ConnectionState.ONLINE else ConnectionState.OFFLINE)
        if (foreground) StartupPerf.mark("online")
        StartupPerf.mark("list_fresh")
        saveIndex(workspaces, index)
        loadRemainingIndex(network, receivedIndex)
        var historyError: String? = null
        var retryHistory = false
        val target = requestedSessionId ?: selected
        val refreshed = try {
            // A newly requested selection owns its own GET after metadata. An existing selection
            // (including its retryable first projection) is safe for this refresh to recover.
            if (readHistory && target != null && observingSession && (requestedSessionId == null || requestedSessionId == selected)) readObservedSnapshot(network, target) else null
        } catch (failure: MobileFailure) {
            if (failure.key != "chat_unavailable") throw failure
            // Metadata already authenticated successfully. A projection/size error belongs to
            // this chat, not host connectivity, and must not revalidate any cached cursor.
            historyError = failure.key
            retryHistory = failure.retryable
            null
        }
        val snapshot = refreshed ?: state.value.snapshot?.takeIf { it.session.id == target }
        val selection = refreshed?.session?.id ?: selected
        if (stored.selectedSessionId != selection) persist(stored.copy(selectedSessionId = selection))
        mutableState.value = state.value.copy(
            hostName = caps.hostName, capabilities = caps.capabilities, workspaces = workspaces,
            presets = presets, sessions = sessions, sessionsTruncated = index.truncated,
            snapshot = state.value.snapshot?.takeIf { it == snapshot } ?: snapshot,
            snapshotStale = snapshot != null && refreshed == null, draft = draftFor(selected), error = historyError,
            demo = caps.upstreamVersion == "fixture", lastSyncedAt = now(), busy = false,
            connection = if (foreground) ConnectionState.ONLINE else ConnectionState.OFFLINE,
        )
        if (retryHistory) scheduleProjectionRetry()
        if (refreshed != null && observingSession) {
            StartupPerf.mark("chat_fresh")
            reconcileSnapshot(refreshed)
        }
        if (stored.pending != null) resolveLocked(reloadSnapshot = false)
        // When observing, a successful GET does not mean the stream recovered. Reset only on
        // a real SSE snapshot; otherwise repeated stream-open failures would retry forever.
        if (requestedSessionId == null && (snapshot == null || !caps.capabilities.liveSnapshots)) reconnectAttempt = 0
        if (foreground && state.value.connection == ConnectionState.ONLINE) {
            startObserver()
            startListRefresh()
            if (prefetchEnabled && requestedSessionId == null) prefetch.start(sessions.filterNot { observingSession && it.id == selected })
        }
    }
    override suspend fun selectSession(sessionId: String) {
        stopListRefresh()
        val task = synchronized(selectionGuard) {
            selection?.cancel()
            historyRead?.cancel()
            val revision = ++selectionRevision
            requestedSessionId = sessionId
            observingSession = true
            cancelProjectionRetry()
            prefetch.cancel()
            val cached = diskHistory.get(sessionId)?.takeIf { entry ->
                (state.value.sessions.any { it.id == sessionId && it.workspaceId == entry.session.workspaceId } ||
                    (state.value.sessionsTruncated && state.value.sessions.none { it.id == sessionId })) &&
                    state.value.workspaces.any { it.id == entry.session.workspaceId }
            }
            mutableState.value = state.value.copy(observingSession = true,
                snapshot = cached ?: state.value.snapshot, snapshotStale = cached != null || state.value.snapshotStale,
                draft = if (cached != null) draftFor(sessionId) else state.value.draft)
            if (cached != null) StartupPerf.mark("chat_cache_shown")
            scope.async(start = CoroutineStart.LAZY) {
                action {
                    try { selectLocked(sessionId, revision) }
                    finally {
                        if (revision == selectionRevision || !observingSession)
                            mutableState.value = state.value.copy(busy = false)
                    }
                }
            }.also { selection = it }
        }
        task.start()
        try { task.await() }
        catch (cancelled: CancellationException) {
            // Back/switch cancels this read, not the caller or an admitted mutation.
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                task.cancel()
                throw cancelled
            }
            if (!task.isCancelled) throw cancelled
        } finally { synchronized(selectionGuard) { if (selection === task) selection = null } }
    }
    override suspend fun leaveSession() {
        synchronized(selectionGuard) {
            if (!observingSession && selection == null && historyRead == null && observer == null) return
            observingSession = false
            cancelProjectionRetry()
            mutableState.value = state.value.copy(observingSession = false, snapshotStale = state.value.snapshot != null,
                error = state.value.error?.takeUnless { it == "chat_unavailable" })
            selectionRevision++; requestedSessionId = null
            selection?.cancel(); selection = null
            historyRead?.cancel(); historyRead = null
        }
        stopObserver()
        startListRefresh()
        if (prefetchEnabled && foreground && state.value.connection == ConnectionState.ONLINE)
            prefetch.start(state.value.sessions.filter { diskHistory.get(it.id) == null })
    }
    /** Cancelling a presentation read never cancels its refresh/receipt owner. Call under admission. */
    private suspend fun readObservedSnapshot(network: HostApi, sessionId: String): SessionSnapshot? = coroutineScope {
        val task = synchronized(selectionGuard) {
            if (!observingSession) return@coroutineScope null
            historyRead?.cancel()
            async(start = CoroutineStart.LAZY) { network.snapshot(sessionId) }.also { historyRead = it }
        }
        task.start()
        try { task.await() }
        catch (cancelled: CancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!task.isCancelled) throw cancelled
            null
        } finally { synchronized(selectionGuard) { if (historyRead === task) historyRead = null } }
    }
    private suspend fun selectLocked(sessionId: String, revision: Long, metadataReady: Boolean = false) {
        restoreLocked()
        if (!foreground) throw MobileFailure("offline_no_send")
        if (!validId(sessionId)) throw MobileFailure("invalid_selection")
        // Navigation owns the target snapshot read below; metadata recovery must not project the
        // old chat or open this target twice. Durable receipt recovery inside refresh is unchanged.
        if (!metadataReady && (state.value.connection != ConnectionState.ONLINE || (!state.value.sessionsTruncated && state.value.sessions.none { it.id == sessionId }))) refreshLocked(readHistory = false)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (revision != selectionRevision || !observingSession) return
        stopObserver()
        // Cached history is presentation only. Never reconcile a receipt or use its cursor to stop work.
        val before = state.value
        val cached = (history.get(sessionId) ?: diskHistory.get(sessionId))?.takeIf { entry ->
            (state.value.sessions.any { it.id == sessionId && it.workspaceId == entry.session.workspaceId } ||
                    (state.value.sessionsTruncated && state.value.sessions.none { it.id == sessionId })) &&
                state.value.workspaces.any { it.id == entry.session.workspaceId }
        }
        mutableState.value = state.value.copy(busy = true, error = null,
            snapshot = cached ?: state.value.snapshot?.takeUnless { it.session.id == sessionId },
            snapshotStale = cached != null, draft = if (cached != null) draftFor(sessionId) else state.value.draft)
        try {
            val snapshot = readChatFirstSnapshot(api(), sessionId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (revision != selectionRevision || !observingSession) return
            if (state.value.workspaces.none { it.id == snapshot.session.workspaceId }) throw MobileFailure("invalid_selection")
            if (state.value.sessions.none { it.id == sessionId }) {
                // A bounded index may omit a target; a point-authorized snapshot is sufficient.
                mutableState.value = state.value.copy(sessions = state.value.sessions + snapshot.session)
            }
            persist(stored.copy(selectedSessionId = sessionId))
            synchronized(selectionGuard) { if (revision == selectionRevision) requestedSessionId = null }
            mutableState.value = MobileReducer.replaceSnapshot(state.value, snapshot, now()).copy(
                draft = draftFor(sessionId), busy = false,
            )
            StartupPerf.mark("chat_fresh")
            reconcileSnapshot(snapshot)
            if (observer == null) startObserver()
        } catch (cancelled: CancellationException) {
            // Restore the previous selection only before a durable navigation write took place.
            if (state.value.snapshotStale || stored.selectedSessionId != sessionId) mutableState.value = state.value.copy(
                snapshot = before.snapshot, snapshotStale = before.snapshotStale || (!observingSession && before.snapshot != null), draft = before.draft)
            throw cancelled
        }
    }
    override suspend fun updateDraft(text: String) {
        if (closed) return
        if (text.toByteArray().size > 32 * 1024) {
            mutableState.value = state.value.copy(error = "invalid_text")
            return
        }
        val sessionId = state.value.snapshot?.session?.id
        if (sessionId == null) { mutableState.value = state.value.copy(error = "invalid_selection"); return }
        // Publish BEFORE waiting for secure disk or an in-flight network admission. The session ID
        // is captured here, so an edit queued during navigation cannot overwrite another draft.
        val revision = draftSequence.incrementAndGet()
        liveDrafts[sessionId] = DraftEdit(revision, text)
        mutableState.value = state.value.copy(draft = text, error = null)
        action {
            restoreLocked()
            val latest = liveDrafts[sessionId] ?: return@action
            if (latest.revision != revision) return@action // Coalesce burst edits that waited on I/O.
            val drafts = stored.drafts.toMutableMap().apply {
                if (text.isEmpty()) remove(sessionId) else put(sessionId, text)
            }
            if (drafts.size > 64) throw MobileFailure("storage_failed")
            persist(stored.copy(drafts = drafts, notSentSessions = if (text.isEmpty()) stored.notSentSessions - sessionId else stored.notSentSessions))
        }
    }
    override suspend fun createSession(workspaceId: String, presetId: String?, requestId: String) = action {
        restoreLocked(); online()
        observingSession = true
        mutableState.value = state.value.copy(observingSession = true)
        if (state.value.capabilities?.sessions != true) throw MobileFailure("unsupported")
        if (state.value.workspaces.none { it.id == workspaceId && it.canExecute } ||
            (presetId != null && state.value.presets.none { it.id == presetId })) throw MobileFailure("invalid_selection")
        if (!validRequestId(requestId)) throw MobileFailure("invalid_selection")
        dispatch(StoredCommand(requestId, "create", workspaceId = workspaceId, presetId = presetId))
    }
    override suspend fun markSendNotSent(sessionId: String, text: String) = action {
        restoreLocked()
        if (stored.pending == null && validId(sessionId) && text.toByteArray().size <= 32768)
            persist(stored.copy(drafts = stored.drafts + (sessionId to text), notSentSessions = stored.notSentSessions + sessionId))
    }
    override suspend fun sendMessage(text: String) = sendMessage(text, null, emptyList())
    override suspend fun sendMessage(text: String, delivery: String?, uploadIds: List<String>) {
        val origin = state.value.snapshot?.session?.id
        val blocked = state.value.busy
        action {
            restoreLocked()
            val command = try {
                online()
                if (blocked || origin == null || stored.selectedSessionId != origin) throw MobileFailure("invalid_selection")
                if (state.value.capabilities?.textPrompt != true) throw MobileFailure("unsupported")
                if ((text.isBlank() && uploadIds.isEmpty()) || text.toByteArray().size > 32 * 1024 || '\u0000' in text) throw MobileFailure("invalid_text")
                if (delivery !in setOf(null, "queue", "steer") || (delivery == "steer" && state.value.capabilities?.steer != true) ||
                    (uploadIds.isNotEmpty() && state.value.capabilities?.attachments != true) || uploadIds.size > 20 || uploadIds.distinct().size != uploadIds.size) throw MobileFailure("unsupported")
                val snapshot = executableSnapshot()
                if (MobileReducer.blockingPromptCount(state.value, snapshot.session.id) >= MobileReducer.MAX_ACCEPTED_PROMPTS) throw MobileFailure("command_unresolved")
                StoredCommand(UUID.randomUUID().toString(), "send", sessionId = snapshot.session.id, text = text,
                    queuedWhileRunning = snapshot.activity in setOf("running", "waiting"), delivery = delivery,
                    uploadIds = uploadIds.takeIf { it.isNotEmpty() },
                    attachmentSources = stored.attachmentDrafts[snapshot.session.id].orEmpty().filter { it.uploadId in uploadIds })
            } catch (failure: MobileFailure) {
                // A refused local admission made no transport call. Never let reconnect erase the outcome.
                if (origin != null && stored.pending == null && text.toByteArray().size <= 32768 && failure.key !in setOf("revoked", "unauthorized", "forbidden", "not_paired"))
                    persist(stored.copy(drafts = stored.drafts + (origin to text), notSentSessions = stored.notSentSessions + origin))
                throw failure
            }
            // Store draft and exact command in the SAME atomic encrypted write before dispatch.
            dispatch(command, stored.drafts + (requireNotNull(command.sessionId) to text))
        }
    }
    private fun discardStaging(session: String, sources: List<dev.dshmobile.app.model.DraftAttachment>) {
        val host = stored.host ?: return
        if (stored.uploadProtectionUntil > now()) return
        val protected = stored.acceptedPrompts.flatMap { it.uploadIds.orEmpty() } + stored.pending?.uploadIds.orEmpty() + stored.possiblyUsedUploads.filterValues { it > now() }.keys
        val referenced = stored.attachmentDrafts.values.flatten().mapNotNull { it.uploadId }
        val ids = sources.mapNotNull { it.uploadId }.distinct().filter { it !in protected && it !in referenced }
        if (ids.isEmpty()) return
        scope.launch {
            // Best effort and independent of foreground/UI; never retry a mutation or an admitted source.
            val cleanupApi = HostApi(host.endpoint, host.deviceToken)
            try { ids.forEach { id -> runCatching { cleanupApi.discardUpload(session, id) } } }
            finally { cleanupApi.close() }
        }
    }
    override suspend fun saveAttachments(sessionId: String, items: List<dev.dshmobile.app.model.DraftAttachment>) = action {
        restoreLocked()
        if (!validId(sessionId) || items.size > 20 || items.sumOf { it.size } > dev.dshmobile.app.model.PhoneRules.MESSAGE_LIMIT || items.any { !validAttachmentDraft(it) }) throw MobileFailure("invalid_selection")
        saveAttachmentsLocked(sessionId, items)
    }
    override suspend fun editAttachments(sessionId: String, add: List<dev.dshmobile.app.model.DraftAttachment>, remove: Set<String>,
        replace: dev.dshmobile.app.model.DraftAttachment?, expectedUploadId: String?) = action {
        restoreLocked()
        val current = stored.attachmentDrafts[sessionId].orEmpty()
        val retained = current.filterNot { it.localId in remove }.map { source ->
            if (replace?.localId == source.localId && source.uploadId == expectedUploadId) replace else source
        }
        val items = retained + add.filter { candidate -> candidate.localId !in remove && retained.none { it.localId == candidate.localId } }
        if (!validId(sessionId) || items.size > 20 || items.sumOf { it.size } > dev.dshmobile.app.model.PhoneRules.MESSAGE_LIMIT || items.any { !validAttachmentDraft(it) }) throw MobileFailure("invalid_selection")
        saveAttachmentsLocked(sessionId, items)
    }
    private suspend fun saveAttachmentsLocked(sessionId: String, items: List<dev.dshmobile.app.model.DraftAttachment>) {
        val previous = stored.attachmentDrafts
        persist(stored.copy(attachmentDrafts = if (items.isEmpty()) stored.attachmentDrafts - sessionId else (stored.attachmentDrafts + (sessionId to items)).entries.toList().takeLast(64).associate { it.toPair() }))
        previous.forEach { (session, old) -> discardStaging(session, old.filter { source -> stored.attachmentDrafts[session].orEmpty().none { it.uploadId == source.uploadId } }) }
    }
    override suspend fun saveAnswerDraft(sessionId: String, callId: String, answer: dev.dshmobile.app.model.QuestionAnswer) = action {
        restoreLocked()
        if (!validId(sessionId) || !validId(callId) || !validAnswerDraft(answer)) throw MobileFailure("invalid_selection")
        persist(stored.copy(answerDrafts = (stored.answerDrafts + (dev.dshmobile.app.model.questionDraftKey(sessionId, callId) to answer)).entries.toList().takeLast(64).associate { it.toPair() }))
    }
    private suspend fun phoneCommand(capability: String, build: (SessionSnapshot) -> StoredCommand) {
        val origin = state.value.snapshot?.session?.id
        action {
            restoreLocked(); online()
            if (origin == null || stored.selectedSessionId != origin || !dev.dshmobile.app.model.PhoneRules.canMutate(state.value)) throw MobileFailure("invalid_selection")
            val caps = state.value.capabilities
            val available = when (capability) { "queue" -> caps?.queue; "rename" -> caps?.rename; "model" -> caps?.models; "answer" -> caps?.questions; else -> false }
            if (available != true) throw MobileFailure("unsupported")
            dispatch(build(requireNotNull(state.value.snapshot)))
        }
    }
    override suspend fun queueAction(itemId: String, action: String) = phoneCommand("queue") { snapshot ->
        if (action == "steer" && state.value.capabilities?.steer != true) throw MobileFailure("unsupported")
        if (action !in setOf("steer", "remove") || snapshot.pendingQueue.none { it.id == itemId } || (action == "steer" && !snapshot.session.running)) throw MobileFailure("invalid_selection")
        StoredCommand(UUID.randomUUID().toString(), "queue", sessionId = snapshot.session.id, itemId = itemId, action = action)
    }
    override suspend fun rename(title: String) = phoneCommand("rename") { snapshot ->
        if (!dev.dshmobile.app.model.PhoneRules.validTitle(title)) throw MobileFailure("invalid_selection")
        StoredCommand(UUID.randomUUID().toString(), "rename", sessionId = snapshot.session.id, title = title)
    }
    override suspend fun selectModel(selection: dev.dshmobile.app.model.ModelSelection) = phoneCommand("model") { snapshot ->
        if (!validId(selection.provider) || !validId(selection.model)) throw MobileFailure("invalid_selection")
        StoredCommand(UUID.randomUUID().toString(), "model", sessionId = snapshot.session.id, selection = selection)
    }
    override suspend fun answer(callId: String, answer: dev.dshmobile.app.model.QuestionAnswer) = phoneCommand("answer") { snapshot ->
        val question = snapshot.pendingQuestions.singleOrNull { it.callId == callId && it.state in setOf("open", "continued") } ?: throw MobileFailure("question_expired")
        if (!dev.dshmobile.app.model.PhoneRules.validAnswer(question, answer, state.value.capabilities?.questionSkip == true)) throw MobileFailure("invalid_selection")
        StoredCommand(UUID.randomUUID().toString(), "answer", sessionId = snapshot.session.id, callId = callId, answer = answer)
    }
    override suspend fun skipQuestion(callId: String) = phoneCommand("answer") { snapshot ->
        if (state.value.capabilities?.questionSkip != true) throw MobileFailure("unsupported")
        if (snapshot.pendingQuestions.none { it.callId == callId && it.state in setOf("open", "continued") }) throw MobileFailure("question_expired")
        StoredCommand(UUID.randomUUID().toString(), "answer", sessionId = snapshot.session.id, callId = callId, skip = true)
    }
    override suspend fun file(sessionId: String, attachmentId: String): ByteArray {
        if (state.value.capabilities?.fileDownload != true || state.value.snapshot?.takeIf { it.session.id == sessionId }?.messages?.none { m -> m.attachments.any { it.id == attachmentId } } != false) throw MobileFailure("unsupported")
        fileReads.acquire()
        try { return api().file(sessionId, attachmentId) } finally { fileReads.release() }
    }
    override suspend fun models(): dev.dshmobile.app.model.ModelCatalog {
        if (state.value.capabilities?.models != true) throw MobileFailure("unsupported")
        return api().models()
    }
    private val uploadQueue = UploadQueue()
    override suspend fun upload(sessionId: String, attachment: dev.dshmobile.app.model.DraftAttachment, open: () -> java.io.InputStream, progress: (Int) -> Unit): dev.dshmobile.app.model.DraftAttachment = uploadQueue.upload(progress) {
        // Recheck after waiting: a queued upload must not bypass current grants/state.
        if (state.value.capabilities?.attachments != true || state.value.snapshot?.session?.id != sessionId || !dev.dshmobile.app.model.PhoneRules.canMutate(state.value)) throw MobileFailure("forbidden")
        val receipt = api().upload(sessionId, attachment.name, attachment.mime, attachment.size, open, progress)
        attachment.copy(uploadId = receipt.uploadId, expiresAt = receipt.expiresAt)
    }
    override suspend fun image(sessionId: String, attachmentId: String): ByteArray {
        if (state.value.capabilities?.attachments != true || state.value.connection != ConnectionState.ONLINE) throw MobileFailure("unsupported")
        return api().image(sessionId, attachmentId)
    }
    override suspend fun cancelRun() {
        val origin = state.value.snapshot
        val blocked = state.value.busy
        action {
            restoreLocked(); online()
            if (blocked || origin == null || stored.selectedSessionId != origin.session.id || state.value.snapshot?.cursor != origin.cursor) throw MobileFailure("invalid_selection")
            if (state.value.capabilities?.cancel != true) throw MobileFailure("unsupported")
            val snapshot = executableSnapshot()
            if (snapshot.activity != "running" || !snapshot.session.running) throw MobileFailure("invalid_selection")
            dispatch(StoredCommand(UUID.randomUUID().toString(), "cancel", sessionId = snapshot.session.id, expectedCursor = origin.cursor))
        }
    }
    private fun executableSnapshot(): SessionSnapshot {
        val snapshot = state.value.snapshot ?: throw MobileFailure("invalid_selection")
        if (state.value.snapshotStale) throw MobileFailure("invalid_selection")
        if (!snapshot.session.canExecute || state.value.workspaces.none { it.id == snapshot.session.workspaceId && it.canExecute }) throw MobileFailure("forbidden")
        if (snapshot.activity !in setOf("idle", "running") && !(snapshot.activity == "waiting" && state.value.capabilities?.questions == true && snapshot.pendingQuestions.isNotEmpty())) throw MobileFailure("unsupported")
        return snapshot
    }
    private suspend fun refreshedSources(session: String, sources: List<dev.dshmobile.app.model.DraftAttachment>, force: Boolean = false): List<dev.dshmobile.app.model.DraftAttachment> =
        sources.map { source ->
            if (!force && source.uploadId != null && source.expiresAt > now()) source else {
                val open = openAttachment ?: throw MobileFailure("attachment_unavailable")
                val receipt = uploadQueue.upload({}) { api().upload(session, source.name, source.mime, source.size, { open(source) }, {}) }
                val refreshed = source.copy(uploadId = receipt.uploadId, expiresAt = receipt.expiresAt)
                fun replace(items: List<dev.dshmobile.app.model.DraftAttachment>) = items.map { if (it.localId == source.localId) refreshed else it }
                // Commit each successful upload before attempting the next one; a later 429 cannot lose it.
                persist(stored.copy(attachmentDrafts = stored.attachmentDrafts.mapValues { (id, items) -> if (id == session) replace(items) else items },
                    acceptedPrompts = stored.acceptedPrompts.map { command -> if (command.sessionId == session) {
                        val updated = replace(command.attachmentSources)
                        command.copy(attachmentSources = updated) // Never rewrite an already admitted command's exact uploadIds.
                    } else command }))
                refreshed
            }
        }
    /** Optional watch storage must never rewrite a proven admission outcome. Failed removal
     * is logged; the durable watch can still settle from rejected or its existing expiry. */
    private suspend fun cleanupWatch(requestId: String) {
        try { unwatchPrompt(requestId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { runCatching { NotificationPosting.logFailure(failure) } }
    }
    private suspend fun dispatch(command: StoredCommand, drafts: Map<String, String> = stored.drafts) {
        // Capture mutation transport before persistence can suspend. ON_STOP retires only reads.
        // Only a fresh proof that no headers left permits bounded transport recovery.
        val host = stored.host ?: throw MobileFailure("not_paired")
        val mutation = HostApi(host.endpoint, host.deviceToken)
        try { dispatchOn(mutation, command, drafts) } finally { mutation.close() }
    }
    private suspend fun commandWithPreHeaderRecovery(initial: HostApi, command: StoredCommand): CommandReceipt {
        var transport = initial
        try {
            for (attempt in 0..2) {
                try { return transport.command(command) }
                catch (failure: MobileFailure) {
                    // A fresh proof is required for EVERY attempt. Once any headers may have left,
                    // receipt-only recovery applies; neither HTTP errors nor lost responses retry.
                    if (command.kind != "send" || !failure.requestNotSent || failure.key != "network_unavailable" || attempt == 2) throw failure
                    delay(preHeaderRetryDelay(attempt))
                    if (transport !== initial) transport.close()
                    val host = stored.host ?: throw failure
                    transport = HostApi(host.endpoint, host.deviceToken) // New VPN route/tunnel, never stale pool.
                }
            }
            error("unreachable")
        } finally { if (transport !== initial) transport.close() }
    }
    private suspend fun dispatchOn(mutation: HostApi, command: StoredCommand, drafts: Map<String, String>) {
        // Persist the watch BEFORE any prompt bytes can leave. A process death cannot admit a
        // phone task whose watch was never stored. No draft text or attachments enter this record.
        if (command.kind == "send" && state.value.capabilities?.promptOutcomes == true) {
            val session = requireNotNull(command.sessionId)
            val title = state.value.snapshot?.session?.takeIf { it.id == session }?.title
                ?: state.value.sessions.firstOrNull { it.id == session }?.title.orEmpty()
            watchPrompt(requireNotNull(stored.host).deviceId, session, command.requestId, title)
        }
        try { persist(stored.copy(pending = command, drafts = drafts, notSentSessions = stored.notSentSessions.filterNot { it == command.sessionId }.toSet())) }
        catch (failure: Exception) { cleanupWatch(command.requestId); throw failure }
        mutableState.value = state.value.copy(pending = command.presentation(), draft = draftFor(stored.selectedSessionId), busy = true, error = null)
        try {
            val receipt = try { commandWithPreHeaderRecovery(mutation, command) } catch (missing: MobileFailure) {
                if (missing.key != "upload_missing" || command.attachmentSources.size != command.uploadIds.orEmpty().size || command.attachmentSources.isEmpty()) throw missing
                // Only an explicit pre-admission 404 authorizes this single automatic continuation.
                // Keep the requestId; uncertain/lost responses never enter this branch.
                val session = requireNotNull(command.sessionId)
                // The pre-admission 404 makes all submitted IDs suspect; invalidate them durably.
                // A retry after a partial refresh can then reuse successes and refresh only failures.
                val stale = command.attachmentSources.map { it.copy(expiresAt = 0) }
                val staleById = stale.associateBy { it.localId }
                persist(stored.copy(pending = null, attachmentDrafts = stored.attachmentDrafts + (session to stored.attachmentDrafts[session].orEmpty().map { staleById[it.localId] ?: it })))
                val sources = refreshedSources(session, stale)
                val refreshed = command.copy(uploadIds = sources.map { requireNotNull(it.uploadId) }, attachmentSources = sources)
                val mapping = sources.associateBy { it.localId }
                val attachments = stored.attachmentDrafts[command.sessionId].orEmpty().map { mapping[it.localId] ?: it }
                persist(stored.copy(pending = refreshed, attachmentDrafts = stored.attachmentDrafts + (command.sessionId to attachments)))
                commandWithPreHeaderRecovery(mutation, refreshed)
            }
            applyReceipt(receipt)
            // A pending receipt has its own short read-only polling budget. A potentially
            // 15-second transcript GET must not hold admission ahead of the first receipt GET.
            if (stored.pending?.status != "pending") reloadCommandSnapshot(command)
        } catch (failure: MobileFailure) {
            if (failure.key == "recovery_hold") {
                // The pinned host refused the command before admission: no receipt exists and
                // the host provably did not dispatch it. Treat it as not sent, never uncertain.
                persist(stored.copy(pending = null,
                    notSentSessions = if (command.kind == "send" && command.sessionId != null) stored.notSentSessions + command.sessionId else stored.notSentSessions))
                cleanupWatch(command.requestId)
                fail(failure.key, failure.retryable)
                mutableState.value = state.value.copy(error = "recovery_hold", busy = false)
                return
            }
            if (failure.requestNotSent) {
                // Only transport evidence BEFORE requestHeadersStart permits this classification.
                // Missing receipts, timeouts after headers, and lost responses remain uncertain.
                persist(stored.copy(pending = null, notSentSessions = if (command.kind == "send" && command.sessionId != null) stored.notSentSessions + command.sessionId else stored.notSentSessions))
                cleanupWatch(command.requestId)
                fail(failure.key, failure.retryable)
                mutableState.value = state.value.copy(error = "offline_no_send", busy = false)
                return
            }
            if (failure.key == "upload_missing") {
                persist(stored.copy(pending = null))
                cleanupWatch(command.requestId)
                mutableState.value = state.value.copy(error = "attachment_unavailable", busy = false)
                return
            }
            if (failure.key == "question_expired" && command.kind == "answer") {
                persist(stored.copy(pending = null))
                mutableState.value = state.value.copy(error = "question_expired", busy = false)
                reloadCommandSnapshot(command)
                return
            }
            // Even a malformed/lost response cannot prove no upstream admission. Never POST again.
            if (stored.pending != null) {
                persist(stored.copy(pending = stored.pending?.copy(status = "uncertain")))
                mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = "command_uncertain", busy = false)
                if (foreground) {
                    try { resolveLocked(reloadSnapshot = true) }
                    catch (lookup: MobileFailure) { fail(lookup.key, lookup.retryable) }
                }
            }
            if (stored.pending == null || failure.key in setOf("unauthorized", "revoked", "forbidden", "incompatible_protocol", "tls_failed", "network_unavailable", "invalid_response", "rate_limited")) fail(failure.key, failure.retryable)
        }
        mutableState.value = state.value.copy(busy = false)
        if (foreground && state.value.connection == ConnectionState.ONLINE) startObserver()
    }
    private suspend fun reloadCommandSnapshot(command: StoredCommand) {
        if (!foreground || !desiredForeground || closed) return
        val target = if (command.kind == "create") stored.selectedSessionId else command.sessionId
        if (target != null) {
            val snapshot = api().snapshot(target)
            if (stored.selectedSessionId == target) mutableState.value = MobileReducer.replaceSnapshot(state.value, snapshot, now())
            reconcileSnapshot(snapshot)
        }
    }
    private suspend fun applyReceipt(receipt: CommandReceipt) {
        val command = stored.pending ?: return
        receipt.checked(command.requestId)
        when (receipt.status) {
            "accepted" -> {
                sessionRevision++
                if (command.kind == "create") {
                    val created = receipt.result?.sessionId ?: throw MobileFailure("invalid_response")
                    persist(stored.copy(pending = null, selectedSessionId = created))
                    mutableAcceptedCreate.value = CreateAcceptance(command.requestId, created)
                    val index = mergeIndex(api().sessions())
                    val snapshot = api().snapshot(created)
                    val sessions = listOf(snapshot.session) + index.items.filterNot { it.id == created }.take(999)
                    mutableState.value = MobileReducer.replaceSnapshot(state.value, snapshot, now()).copy(sessions = sessions,
                        sessionsTruncated = index.truncated, pending = null, draft = draftFor(created), error = null)
                } else if (command.kind == "send") {
                    // Receipt proves admission, even if the DSH inbox has no canonical record yet.
                    val admitted = command.copy(status = if (command.queuedWhileRunning) "queued" else "accepted", acceptedAt = now(), idleSnapshots = 0)
                    val drafts = stored.drafts.toMutableMap()
                    if (drafts[command.sessionId] == command.text) drafts.remove(command.sessionId)
                    persist(stored.copy(pending = null, acceptedPrompts = boundedLocalPrompts(stored.acceptedPrompts + admitted), drafts = drafts,
                        attachmentDrafts = withoutConfirmedAttachments(command)))
                    command.sessionId?.let { if (liveDrafts[it]?.text == command.text) liveDrafts.remove(it) }
                    mutableState.value = state.value.copy(draft = draftFor(stored.selectedSessionId), error = null)
                } else if (command.kind in setOf("queue", "rename", "model", "answer")) {
                    val answeredKey = if (command.kind == "answer") dev.dshmobile.app.model.questionDraftKey(requireNotNull(command.sessionId), requireNotNull(command.callId)) else null
                    persist(stored.copy(pending = null, answerDrafts = if (answeredKey != null) stored.answerDrafts - answeredKey else stored.answerDrafts))
                    mutableState.value = state.value.copy(error = null)
                } else {
                    persist(stored.copy(pending = command.copy(status = "accepted")))
                    mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = null)
                }
            }
            "rejected" -> {
                // Trustworthy rejection releases admission. Exact draft is retained for explicit edits.
                persist(stored.copy(pending = null))
                cleanupWatch(command.requestId)
                mutableState.value = state.value.copy(pending = null, error = "command_rejected")
            }
            else -> {
                // A pending lookup cannot undo an earlier unknown-delivery warning.
                val status = if (command.status == "uncertain" && receipt.status == "pending") "uncertain" else receipt.status
                persist(stored.copy(pending = command.copy(status = status)))
                mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = if (status == "uncertain") "command_uncertain" else null)
                stored.pending?.let(::scheduleReceiptPoll)
            }
        }
    }
    private fun withoutConfirmedAttachments(command: StoredCommand): Map<String, List<dev.dshmobile.app.model.DraftAttachment>> {
        val session = command.sessionId ?: return stored.attachmentDrafts
        val ids = command.uploadIds.orEmpty().toSet()
        val remaining = stored.attachmentDrafts[session].orEmpty().filterNot { it.uploadId in ids }
        return if (remaining.isEmpty()) stored.attachmentDrafts - session else stored.attachmentDrafts + (session to remaining)
    }
    private suspend fun reconcileSnapshot(snapshot: SessionSnapshot?) {
        if (snapshot == null) return
        sessionRevision++
        cacheSnapshot(snapshot)
        val observedAt = now()
        val accepted = stored.acceptedPrompts.mapNotNull { local ->
            if (MobileReducer.reconcilesPrompt(local.presentation(), snapshot) ||
                (local.sessionId == snapshot.session.id && snapshot.pendingQueue.any { it.requestId == local.requestId }) ||
                (local.sessionId == snapshot.session.id && local.itemId != null && snapshot.pendingQueue.none { it.id == local.itemId })) return@mapNotNull null
            if (local.sessionId != snapshot.session.id || local.status == "unconfirmed") return@mapNotNull local
            val idleCut = snapshot.activity == "idle" && !snapshot.session.running
            val advancedIdleCut = idleCut && local.lastIdleCursor != snapshot.cursor
            val idle = when {
                !idleCut -> 0
                advancedIdleCut -> local.idleSnapshots + 1
                else -> local.idleSnapshots
            }
            val since = local.acceptedAt ?: observedAt // Migrate legacy accepted records without inventing admission time.
            val expired = idle >= 2 || observedAt - since >= MobileReducer.ACCEPTED_PROMPT_TIMEOUT_MS
            local.copy(status = if (expired) "unconfirmed" else local.status, acceptedAt = since, idleSnapshots = minOf(idle, 2),
                lastIdleCursor = if (idleCut) snapshot.cursor else null)
        }
        val command = stored.pending
        val prompt = command?.let { MobileReducer.reconcilesPrompt(it.presentation(), snapshot) } == true
        val cancelled = command?.kind == "cancel" && command.status == "accepted" && command.sessionId == snapshot.session.id &&
            !snapshot.session.running && snapshot.activity == "idle"
        if (!prompt && !cancelled && accepted == stored.acceptedPrompts) return
        val drafts = stored.drafts.toMutableMap()
        if (prompt && drafts[command.sessionId] == command.text) drafts.remove(command.sessionId)
        persist(stored.copy(pending = if (prompt || cancelled) null else command, acceptedPrompts = boundedLocalPrompts(accepted), drafts = drafts,
            attachmentDrafts = if (prompt) withoutConfirmedAttachments(command) else stored.attachmentDrafts))
        if (prompt && command?.sessionId != null && liveDrafts[command.sessionId]?.text == command.text) liveDrafts.remove(command.sessionId)
        mutableState.value = state.value.copy(draft = draftFor(stored.selectedSessionId), error = if (prompt || cancelled) null else state.value.error)
    }
    // Storage bound is independent of admission. Only old non-blocking notices may be trimmed.
    private fun boundedLocalPrompts(prompts: List<StoredCommand>): List<StoredCommand> {
        val excess = (prompts.count { it.status == "unconfirmed" } - MAX_LOCAL_PROMPTS).coerceAtLeast(0)
        val discard = prompts.filter { it.status == "unconfirmed" }.take(excess).map { it.requestId }.toSet()
        return prompts.filterNot { it.requestId in discard }
    }
    private suspend fun dismissLocalPrompt(requestId: String) = action {
        restoreLocked()
        val retained = stored.acceptedPrompts.filterNot { it.requestId == requestId }
        if (retained.size != stored.acceptedPrompts.size) persist(stored.copy(acceptedPrompts = retained))
        // No network call: dismissal never cancels, edits or re-POSTs anything on the host.
    }
    private suspend fun resendLocalPrompt(requestId: String) {
        val origin = state.value.snapshot?.session?.id
        val blocked = state.value.busy
        action {
            restoreLocked(); online()
            val original = stored.acceptedPrompts.singleOrNull { it.requestId == requestId } ?: return@action
            if (blocked || origin == null || original.sessionId != origin || stored.selectedSessionId != origin) throw MobileFailure("invalid_selection")
            if (state.value.capabilities?.textPrompt != true) throw MobileFailure("unsupported")
            val snapshot = executableSnapshot()
            if (MobileReducer.blockingPromptCount(state.value, origin) >= MobileReducer.MAX_ACCEPTED_PROMPTS) throw MobileFailure("command_unresolved")
            // Only the explicit, warned user action enters here. Preserve the old record for reconciliation.
            val sources = refreshedSources(origin, original.attachmentSources)
            if (!original.uploadIds.isNullOrEmpty() && sources.size != original.uploadIds.size) throw MobileFailure("attachment_unavailable")
            dispatch(StoredCommand(UUID.randomUUID().toString(), "send", sessionId = snapshot.session.id,
                text = original.text, queuedWhileRunning = snapshot.activity in setOf("running", "waiting"),
                delivery = original.delivery, uploadIds = if (sources.isNotEmpty()) sources.map { requireNotNull(it.uploadId) } else original.uploadIds,
                attachmentSources = sources))
        }
    }
    override suspend fun abandonPending() = action {
        restoreLocked()
        val command = stored.pending ?: return@action
        if (command.status == "sending") throw MobileFailure("command_unresolved")
        val drafts = stored.drafts.toMutableMap()
        if (command.kind == "send") {
            drafts.remove(command.sessionId)
            command.sessionId?.let { liveDrafts.remove(it) }
        }
        persist(stored.copy(pending = null, drafts = drafts))
        mutableState.value = state.value.copy(pending = null, draft = draftFor(stored.selectedSessionId), error = null)
    }
    override suspend fun resolvePending() = action {
        restoreLocked()
        if (stored.pending == null) return@action
        if (!foreground) throw MobileFailure("offline_no_send")
        resolveLocked(reloadSnapshot = true)
    }
    private var receiptPoll: Job? = null
    private var receiptPollId: String? = null
    private fun scheduleReceiptPoll(command: StoredCommand) {
        if (command.kind !in setOf("send", "create", "answer", "queue") || command.status != "pending" || !foreground || closed) return
        if (receiptPollId == command.requestId && receiptPoll?.isActive == true) return
        receiptPoll?.cancel()
        receiptPollId = command.requestId
        receiptPoll = scope.launch {
            // Delay budget 10.75 s, total budget 12 s including slow GETs. Only read-only recovery.
            kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                for (wait in listOf(250L, 500L, 1_000L, 2_000L, 3_000L, 4_000L)) {
                    delay(wait)
                    var settled = false
                    action {
                        val current = stored.pending
                        if (!foreground || current?.requestId != command.requestId || current.status != "pending") settled = true
                        else {
                            resolveLocked(reloadSnapshot = false)
                            settled = stored.pending?.requestId != command.requestId || stored.pending?.status != "pending"
                            if (settled) reloadCommandSnapshot(command)
                        }
                    }
                    if (settled) return@withTimeoutOrNull
                }
            }
            action {
                if (stored.pending?.requestId == command.requestId && stored.pending?.status == "pending") {
                    persist(stored.copy(pending = stored.pending?.copy(status = "uncertain")))
                    mutableState.value = state.value.copy(error = "command_uncertain")
                }
            }
        }
    }
    private suspend fun resolveLocked(reloadSnapshot: Boolean) {
        val command = stored.pending ?: return
        val receipt = api().receipt(command.requestId)
        if (receipt == null) {
            if (stored.pending != null) persist(stored.copy(pending = stored.pending?.copy(status = "uncertain")))
            mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = "command_uncertain")
        } else applyReceipt(receipt)
        if (reloadSnapshot) reloadCommandSnapshot(command)
    }
    /** The first authoritative SSE cut and subsequent ingress share one connection. */
    private suspend fun readChatFirstSnapshot(network: HostApi, sessionId: String): SessionSnapshot {
        if (state.value.capabilities?.liveSnapshots != true) return network.snapshot(sessionId)
        val first = kotlinx.coroutines.CompletableDeferred<SessionSnapshot>()
        firstSnapshotRead = first
        val current = generation
        val ingress = observerIngress(current)
        snapshotIngress = ingress
        val firstDelivered = java.util.concurrent.atomic.AtomicBoolean()
        observer = network.observe(sessionId, onSnapshot = { replacement ->
            if (firstDelivered.compareAndSet(false, true)) first.complete(replacement)
            else ingress.offer(replacement)
        }, onFailure = { failure ->
            if (!first.isCompleted) first.completeExceptionally(failure)
            else scope.launch { action { if (current == generation && foreground) fail(failure.key, failure.retryable) } }
        })
        return try {
            val snapshot = kotlinx.coroutines.withTimeoutOrNull(firstSnapshotTimeoutMs) { first.await() }
            if (snapshot != null) { reconnectAttempt = 0; projectionRetryAttempt = 0; snapshot }
            else { stopObserver(); network.snapshot(sessionId) }
        } catch (cancelled: CancellationException) {
            stopObserver(); throw cancelled
        } catch (failure: MobileFailure) {
            stopObserver()
            // GET is a read-only fallback, never a way to overrule an observed auth rejection.
            if (failure.key in setOf("unauthorized", "revoked", "forbidden")) {
                history.clear(); diskHistory.invalidateMemory(); hideRejectedHistory(failure.key)
                try { network.snapshot(sessionId) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: MobileFailure) { /* Original authorization failure remains terminal. */ }
                throw failure
            }
            if (failure.key in setOf("network_unavailable", "tls_failed")) network.snapshot(sessionId)
            else throw failure
        } finally { if (firstSnapshotRead === first) firstSnapshotRead = null }
    }
    private fun observerIngress(current: Long) = SnapshotIngress<SessionSnapshot>(scope) { replacement ->
        action {
            if (current != generation || !foreground || !observingSession || stored.selectedSessionId != replacement.session.id) return@action
            checkHistoryEpoch()
            if (state.value.workspaces.none { it.id == replacement.session.workspaceId }) throw MobileFailure("invalid_selection")
            mutableState.value = MobileReducer.replaceSnapshot(state.value, replacement, now())
            reconnectAttempt = 0
            projectionRetryAttempt = 0
            reconcileSnapshot(replacement)
            if (stored.pending?.kind == "cancel" && !replacement.session.running) resolveLocked(reloadSnapshot = false)
        }
    }
    private fun startObserver() {
        stopObserver()
        val snapshot = state.value.snapshot ?: return
        if (!observingSession || state.value.snapshotStale || !foreground || closed || state.value.connection != ConnectionState.ONLINE || state.value.capabilities?.liveSnapshots != true) return
        val current = generation
        val ingress = observerIngress(current)
        snapshotIngress = ingress
        observer = api().observe(snapshot.session.id, onSnapshot = { ingress.offer(it) }, onFailure = { failure ->
            scope.launch { action { if (current == generation && foreground) fail(failure.key, failure.retryable) } }
        })
    }
    private fun scheduleReconnect() {
        if (!foreground || closed || stored.host == null || reconnectJob?.isActive == true || reconnectAttempt >= 6) return
        reconnectJob = scope.launch {
            delay(retryDelay(reconnectAttempt++))
            reconnectJob = null
            action { if (foreground && state.value.connection == ConnectionState.OFFLINE) refreshLocked() }
        }
    }
    /** A retryable projection failure (5xx/429) gets a bounded fresh cut; a 4xx payload failure does not. */
    private fun scheduleProjectionRetry() {
        if (!foreground || closed || stored.host == null || projectionRetryJob?.isActive == true || projectionRetryAttempt >= 6) return
        projectionRetryJob = scope.launch {
            delay(retryDelay(projectionRetryAttempt++))
            projectionRetryJob = null
            action {
                if (foreground && !closed && observingSession && state.value.connection == ConnectionState.ONLINE &&
                    (requestedSessionId != null || stored.selectedSessionId != null)) refreshLocked()
            }
        }
    }
    private fun cancelProjectionRetry() {
        projectionRetryJob?.cancel(); projectionRetryJob = null; projectionRetryAttempt = 0
    }
    override suspend fun setForeground(active: Boolean) {
        val revision = synchronized(lifecycle) {
            if (closed) return
            desiredForeground = active
            foregroundRevision++
            if (!active) {
                foreground = false
                unregisterNetworkWake?.invoke(); unregisterNetworkWake = null
                networkWakeJob?.cancel(); networkWakeJob = null
                receiptPoll?.cancel(); receiptPoll = null; receiptPollId = null
                stopListRefresh(); stopObserver(); cancelReconnect(); cancelProjectionRetry(); prefetch.cancel(); cancelIndex()
                api?.close(); api = null
                pairingTransport?.close()
            }
            foregroundRevision
        }
        action {
            val wasForeground = synchronized(lifecycle) {
                if (revision != foregroundRevision || desiredForeground != active || closed) return@action
                foreground.also {
                    foreground = active
                    if (active && unregisterNetworkWake == null) unregisterNetworkWake = networkWake?.invoke(::networkRestored)
                }
            }
            restoreLocked()
            if (!active) {
                synchronized(lifecycle) { api?.close(); api = null }
                cacheMutex.withLock { diskHistory.flush() }
                if (stored.host != null && state.value.connection !in setOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE))
                    mutableState.value = state.value.copy(connection = ConnectionState.OFFLINE, busy = false)
            } else if (!wasForeground && stored.host != null && desiredForeground) {
                reconnectAttempt = 0
                refreshLocked()
            }
        }
    }
    override suspend fun forget() = action {
        stopListRefresh(); stopObserver(); cancelReconnect(); cancelProjectionRetry(); prefetch.cancel(); cancelIndex()
        // Credentials are the mandatory outcome; optional cache/key failures cannot gate it.
        try { store.clear() }
        catch (_: Exception) { store.reset() }
        try { cacheMutex.withLock { diskHistory.clear() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Credential deletion succeeded; cache is not authority. */ }
        val abandonedDrafts = stored.attachmentDrafts
        stored = stored.copy(attachmentDrafts = emptyMap())
        abandonedDrafts.forEach { (session, sources) -> discardStaging(session, sources) }
        api?.close(); api = null
        historyEpoch = historyStore.authority.epoch
        receiptPoll?.cancel(); receiptPoll = null; receiptPollId = null; mutableAcceptedCreate.value = null
        stored = StoredState(); restored = true; liveDrafts.clear(); history.clear(); observingSession = false
        mutableState.value = MobileState(localPromptActions = promptActions, draftsRestored = true)
    }
    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true; desiredForeground = false; foreground = false; foregroundRevision++
            unregisterNetworkWake?.invoke(); unregisterNetworkWake = null
            stopListRefresh(); stopObserver(); cancelReconnect(); prefetch.cancel(); cancelIndex()
            api?.close(); api = null; pairingTransport?.close()
        }
        removeHistoryListener?.invoke(); removeHistoryListener = null
        repositoryJob.cancel()
    }
    private companion object { const val MAX_LOCAL_PROMPTS = 32 }

    private fun validateStoredCommand(command: StoredCommand) {
        if (!validRequestId(command.requestId) || command.kind !in setOf("send", "create", "cancel", "queue", "rename", "model", "answer") ||
            command.status !in setOf("sending", "pending", "accepted", "queued", "unconfirmed", "uncertain") ||
            command.acceptedAt?.let { it < 0 } == true || command.idleSnapshots !in 0..2 ||
            (command.sessionId != null && !validId(command.sessionId)) || command.text.orEmpty().toByteArray().size > 32 * 1024 ||
            (command.kind == "send" && ((command.text.isNullOrBlank() && command.uploadIds.isNullOrEmpty()) || command.sessionId == null)) ||
            (command.delivery !in setOf(null, "queue", "steer")) || command.uploadIds.orEmpty().let { it.size > 20 || it.distinct().size != it.size || it.any { id -> !validId(id) } } ||
            (command.kind == "queue" && (command.sessionId == null || !validId(command.itemId.orEmpty()) || command.action !in setOf("steer", "remove"))) ||
            (command.kind == "rename" && (command.sessionId == null || !dev.dshmobile.app.model.PhoneRules.validTitle(command.title.orEmpty()))) ||
            (command.kind == "model" && (command.sessionId == null || command.selection == null || !validId(command.selection.provider) || !validId(command.selection.model))) ||
            (command.kind == "answer" && (command.sessionId == null || !validId(command.callId.orEmpty()) || (command.skip == (command.answer != null)))) ||
            (command.kind == "cancel" && (command.sessionId == null || command.expectedCursor == null || command.expectedCursor < -1)) ||
            (command.kind == "create" && (command.workspaceId == null || !validId(command.workspaceId)))) throw MobileFailure("storage_failed")
    }
}
