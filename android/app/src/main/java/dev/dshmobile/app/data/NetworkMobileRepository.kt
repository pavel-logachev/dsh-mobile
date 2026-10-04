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
    NetworkMobileRepository(EncryptedStateStore(context.applicationContext), scope, BuildConfig.DEBUG)

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
) : MobileRepository {
    private val repositoryJob = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + repositoryJob)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(MobileState())
    override val state: StateFlow<MobileState> = mutableState.asStateFlow()
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
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private data class DraftEdit(val revision: Long, val text: String)
    private val draftSequence = java.util.concurrent.atomic.AtomicLong()
    private val liveDrafts = java.util.concurrent.ConcurrentHashMap<String, DraftEdit>()
    private fun draftFor(sessionId: String?): String = sessionId?.let { liveDrafts[it]?.text ?: stored.drafts[it] }.orEmpty()

    private suspend fun action(block: suspend () -> Unit) {
        mutex.withLock {
            if (closed) return
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                fail(if (failure is MobileFailure) failure.key else "storage_failed")
            }
        }
    }
    private fun fail(key: String) {
        val connection = when (key) {
            "revoked", "unauthorized" -> ConnectionState.REVOKED
            "incompatible_protocol" -> ConnectionState.INCOMPATIBLE
            "network_unavailable", "tls_failed", "invalid_response", "host_error", "rate_limited" -> ConnectionState.OFFLINE
            else -> state.value.connection
        }
        mutableState.value = state.value.copy(connection = connection, busy = false, error = key)
        if (connection != ConnectionState.ONLINE) stopObserver()
        if (connection == ConnectionState.OFFLINE) scheduleReconnect()
    }
    private suspend fun persist(value: StoredState) {
        try { store.write(value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { safeDebugDiagnostic("state.persist", failure); throw MobileFailure("storage_failed") }
        stored = value
        mutableState.value = state.value.copy(pending = value.pending?.presentation())
    }
    private fun stopObserver() {
        generation++
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
        api ?: run {
            val host = stored.host ?: throw MobileFailure("not_paired")
            HostApi(host.endpoint, host.deviceToken).also { api = it }
        }
    }
    private fun online() {
        if (stored.host == null) throw MobileFailure("not_paired")
        if (!foreground || state.value.connection != ConnectionState.ONLINE) throw MobileFailure("offline_no_send")
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
            if (stored.drafts.size > 64 || stored.drafts.any { !validId(it.key) || it.value.toByteArray().size > 32 * 1024 }) throw MobileFailure("storage_failed")
            synchronized(lifecycle) {
                if (foreground && desiredForeground && !closed) api = HostApi(host.endpoint, host.deviceToken)
            }
        } else if (stored.pending != null || stored.drafts.isNotEmpty() || stored.selectedSessionId != null) {
            throw MobileFailure("storage_failed")
        }
        restored = true
        if (stored.pending?.status == "sending" || stored.pending?.status == "pending") {
            // A previous process could have dispatched. Only receipt/canonical reads may resolve it.
            persist(stored.copy(pending = stored.pending?.copy(status = "uncertain")))
        }
        mutableState.value = MobileState(
            paired = host != null, hostName = host?.hostName.orEmpty(),
            remoteMode = host?.endpoint?.relay != null, relayHost = host?.endpoint?.relay?.let { safeRelayOrigin(it) },
            connection = if (host == null) ConnectionState.DISCONNECTED else ConnectionState.OFFLINE,
            draft = draftFor(stored.selectedSessionId), pending = stored.pending?.presentation(),
            error = if (stored.pending != null) "command_uncertain" else null,
        )
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
            persist(StoredState(host = host))
            checkpoint = "pair.close-bootstrap"
            pairingApi.closeAndAwait() // Suspend outside lifecycle monitor; no bootstrap overlap.
            checkpoint = "pair.create-device-transport"
            synchronized(lifecycle) {
                if (foreground && desiredForeground && !closed) api = HostApi(host.endpoint, host.deviceToken)
            }
            mutableState.value = MobileState(paired = true, hostName = host.hostName,
                remoteMode = host.endpoint.relay != null, relayHost = host.endpoint.relay?.let { safeRelayOrigin(it) },
                connection = ConnectionState.SYNCING, busy = true)
            checkpoint = "pair.device-refresh"
            if (foreground) refreshLocked() else mutableState.value = state.value.copy(connection = ConnectionState.OFFLINE, busy = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { safeDebugDiagnostic(checkpoint, failure); throw failure }
        finally { pairingTransport = null; pairingApi.close() }
    }
    override suspend fun refresh() = action {
        restoreLocked()
        if (stored.host == null) throw MobileFailure("not_paired")
        if (foreground) refreshLocked()
    }
    private suspend fun refreshLocked() {
        val network = api()
        stopObserver()
        mutableState.value = state.value.copy(connection = ConnectionState.SYNCING, busy = true, error = null)
        val caps = network.capabilities()
        val workspaces = network.workspaces()
        val presets = network.presets()
        val index = if (caps.capabilities.sessions) network.sessions() else HostApi.SessionIndex(emptyList(), false)
        val sessions = index.items
        if (sessions.any { session -> workspaces.none { it.id == session.workspaceId } }) throw MobileFailure("invalid_response")
        val selected = stored.selectedSessionId?.takeIf { id -> sessions.any { it.id == id } }
        val snapshot = if (selected != null) network.snapshot(selected) else null
        if (stored.selectedSessionId != selected) persist(stored.copy(selectedSessionId = selected))
        mutableState.value = state.value.copy(
            hostName = caps.hostName, capabilities = caps.capabilities, workspaces = workspaces,
            presets = presets, sessions = sessions, sessionsTruncated = index.truncated, snapshot = snapshot, draft = draftFor(selected),
            demo = caps.upstreamVersion == "fixture", lastSyncedAt = now(), busy = false,
            connection = if (foreground) ConnectionState.ONLINE else ConnectionState.OFFLINE,
        )
        reconcileSnapshot(snapshot)
        if (stored.pending != null) resolveLocked(reloadSnapshot = false)
        // When observing, a successful GET does not mean the stream recovered. Reset only on
        // a real SSE snapshot; otherwise repeated stream-open failures would retry forever.
        if (snapshot == null || !caps.capabilities.liveSnapshots) reconnectAttempt = 0
        if (foreground && state.value.connection == ConnectionState.ONLINE) startObserver()
    }
    override suspend fun selectSession(sessionId: String) = action {
        restoreLocked()
        if (state.value.connection != ConnectionState.ONLINE || !foreground) throw MobileFailure("offline_no_send")
        if (!validId(sessionId) || state.value.sessions.none { it.id == sessionId }) throw MobileFailure("invalid_selection")
        stopObserver()
        mutableState.value = state.value.copy(busy = true, error = null)
        val snapshot = api().snapshot(sessionId)
        persist(stored.copy(selectedSessionId = sessionId))
        mutableState.value = MobileReducer.replaceSnapshot(state.value, snapshot, now()).copy(
            draft = draftFor(sessionId), busy = false,
        )
        reconcileSnapshot(snapshot)
        startObserver()
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
            persist(stored.copy(drafts = drafts))
        }
    }
    override suspend fun createSession(workspaceId: String, presetId: String?) = action {
        restoreLocked(); online()
        if (state.value.capabilities?.sessions != true) throw MobileFailure("unsupported")
        if (state.value.workspaces.none { it.id == workspaceId && it.canExecute } ||
            (presetId != null && state.value.presets.none { it.id == presetId })) throw MobileFailure("invalid_selection")
        dispatch(StoredCommand(UUID.randomUUID().toString(), "create", workspaceId = workspaceId, presetId = presetId))
    }
    override suspend fun sendMessage(text: String) {
        val origin = state.value.snapshot?.session?.id
        val blocked = state.value.busy
        action {
            restoreLocked(); online()
            if (blocked || origin == null || stored.selectedSessionId != origin) throw MobileFailure("invalid_selection")
            if (state.value.capabilities?.textPrompt != true) throw MobileFailure("unsupported")
            if (text.isBlank() || text.toByteArray().size > 32 * 1024) throw MobileFailure("invalid_text")
            val snapshot = executableSnapshot()
            val command = StoredCommand(UUID.randomUUID().toString(), "send", sessionId = snapshot.session.id, text = text)
            // Store draft and exact command in the SAME atomic encrypted write before dispatch.
            dispatch(command, stored.drafts + (snapshot.session.id to text))
        }
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
        if (!snapshot.session.canExecute || state.value.workspaces.none { it.id == snapshot.session.workspaceId && it.canExecute }) throw MobileFailure("forbidden")
        if (snapshot.activity !in setOf("idle", "running")) throw MobileFailure("unsupported")
        return snapshot
    }
    private suspend fun dispatch(command: StoredCommand, drafts: Map<String, String> = stored.drafts) {
        persist(stored.copy(pending = command, drafts = drafts))
        mutableState.value = state.value.copy(pending = command.presentation(), draft = draftFor(stored.selectedSessionId), busy = true, error = null)
        try {
            val receipt = api().command(command)
            applyReceipt(receipt)
            reloadCommandSnapshot(command)
        } catch (failure: MobileFailure) {
            // Even a malformed/lost response cannot prove no upstream admission. Never POST again.
            if (stored.pending != null) {
                persist(stored.copy(pending = stored.pending?.copy(status = "uncertain")))
                mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = "command_uncertain", busy = false)
                if (foreground) {
                    try { resolveLocked(reloadSnapshot = true) }
                    catch (lookup: MobileFailure) { fail(lookup.key) }
                }
            }
            if (failure.key in setOf("unauthorized", "revoked", "forbidden", "incompatible_protocol", "tls_failed", "network_unavailable", "invalid_response", "rate_limited")) fail(failure.key)
        }
        mutableState.value = state.value.copy(busy = false)
        if (foreground && state.value.connection == ConnectionState.ONLINE) startObserver()
    }
    private suspend fun reloadCommandSnapshot(command: StoredCommand) {
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
                if (command.kind == "create") {
                    val created = receipt.result?.sessionId ?: throw MobileFailure("invalid_response")
                    persist(stored.copy(pending = null, selectedSessionId = created))
                    val index = api().sessions()
                    val snapshot = api().snapshot(created)
                    val sessions = if (index.items.any { it.id == created }) index.items else listOf(snapshot.session) + index.items.take(999)
                    mutableState.value = state.value.copy(sessions = sessions, sessionsTruncated = index.truncated, snapshot = snapshot, pending = null, draft = draftFor(created), error = null)
                } else if (command.kind == "send") {
                    // Receipt proves admission; remove optimism only when canonical user record arrives.
                    persist(stored.copy(pending = command.copy(status = "accepted")))
                    mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = null)
                } else {
                    persist(stored.copy(pending = command.copy(status = "accepted")))
                    mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = null)
                }
            }
            "rejected" -> {
                // Trustworthy rejection releases admission. Exact draft is retained for explicit edits.
                persist(stored.copy(pending = null))
                mutableState.value = state.value.copy(pending = null, error = "command_rejected")
            }
            else -> {
                persist(stored.copy(pending = command.copy(status = receipt.status)))
                mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = if (receipt.status == "uncertain") "command_uncertain" else null)
            }
        }
    }
    private suspend fun reconcileSnapshot(snapshot: SessionSnapshot?) {
        val command = stored.pending ?: return
        if (snapshot == null) return
        val prompt = MobileReducer.reconcilesPrompt(command.presentation(), snapshot)
        val cancelled = command.kind == "cancel" && command.status == "accepted" && command.sessionId == snapshot.session.id &&
            !snapshot.session.running && snapshot.activity == "idle"
        if (!prompt && !cancelled) return
        val drafts = stored.drafts.toMutableMap()
        if (prompt && drafts[command.sessionId] == command.text) drafts.remove(command.sessionId)
        if (prompt && command.sessionId != null && liveDrafts[command.sessionId]?.text == command.text) liveDrafts.remove(command.sessionId)
        persist(stored.copy(pending = null, drafts = drafts))
        mutableState.value = state.value.copy(pending = null, draft = draftFor(stored.selectedSessionId), error = null)
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
    private suspend fun resolveLocked(reloadSnapshot: Boolean) {
        val command = stored.pending ?: return
        val receipt = api().receipt(command.requestId)
        if (receipt == null) {
            persist(stored.copy(pending = command.copy(status = "uncertain")))
            mutableState.value = state.value.copy(pending = stored.pending?.presentation(), error = "command_uncertain")
        } else applyReceipt(receipt)
        if (reloadSnapshot && stored.pending != null) reloadCommandSnapshot(command)
    }
    private fun startObserver() {
        stopObserver()
        val snapshot = state.value.snapshot ?: return
        if (!foreground || closed || state.value.connection != ConnectionState.ONLINE || state.value.capabilities?.liveSnapshots != true) return
        val current = generation
        observer = api().observe(snapshot.session.id, onSnapshot = { replacement ->
            scope.launch {
                action {
                    if (current != generation || !foreground || stored.selectedSessionId != replacement.session.id) return@action
                    mutableState.value = MobileReducer.replaceSnapshot(state.value, replacement, now())
                    reconnectAttempt = 0
                    reconcileSnapshot(replacement)
                    // Host pending -> accepted may be resolved when a material state change arrives.
                    if (stored.pending?.kind == "cancel" && !replacement.session.running) resolveLocked(reloadSnapshot = false)
                }
            }
        }, onFailure = { failure ->
            scope.launch { action { if (current == generation && foreground) fail(failure.key) } }
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
    override suspend fun setForeground(active: Boolean) {
        val revision = synchronized(lifecycle) {
            if (closed) return
            desiredForeground = active
            foregroundRevision++
            if (!active) {
                foreground = false
                stopObserver(); cancelReconnect()
                api?.close(); api = null
                pairingTransport?.close()
            }
            foregroundRevision
        }
        action {
            val wasForeground = synchronized(lifecycle) {
                if (revision != foregroundRevision || desiredForeground != active || closed) return@action
                foreground.also { foreground = active }
            }
            restoreLocked()
            if (!active) {
                synchronized(lifecycle) { api?.close(); api = null }
                if (stored.host != null && state.value.connection !in setOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE))
                    mutableState.value = state.value.copy(connection = ConnectionState.OFFLINE, busy = false)
            } else if (!wasForeground && stored.host != null && desiredForeground) {
                reconnectAttempt = 0
                refreshLocked()
            }
        }
    }
    override suspend fun forget() = action {
        stopObserver(); cancelReconnect()
        store.clear() // If this fails, report storage_failed and never claim the device was forgotten.
        api?.close(); api = null
        stored = StoredState(); restored = true; liveDrafts.clear()
        mutableState.value = MobileState()
    }
    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true; desiredForeground = false; foreground = false; foregroundRevision++
            stopObserver(); cancelReconnect()
            api?.close(); api = null; pairingTransport?.close()
        }
        repositoryJob.cancel()
    }
    private fun validateStoredCommand(command: StoredCommand) {
        if (!validRequestId(command.requestId) || command.kind !in setOf("send", "create", "cancel") ||
            command.status !in setOf("sending", "pending", "accepted", "uncertain") ||
            (command.sessionId != null && !validId(command.sessionId)) || command.text.orEmpty().toByteArray().size > 32 * 1024 ||
            (command.kind == "send" && (command.text.isNullOrBlank() || command.sessionId == null)) ||
            (command.kind == "cancel" && (command.sessionId == null || command.expectedCursor == null || command.expectedCursor < -1)) ||
            (command.kind == "create" && (command.workspaceId == null || !validId(command.workspaceId)))) throw MobileFailure("storage_failed")
    }
}
