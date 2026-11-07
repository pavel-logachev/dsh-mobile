# Android implementation seam

Package `dev.dshmobile.app`. UI and data workers share this contract; communicate changes through the parent before diverging. Build worker owns Gradle/manifest/general resources, data worker owns `data/**` and `model/**`, UI worker owns MainActivity and `ui/**`, `values/mobile_strings.xml`, `values-ru/mobile_strings.xml`. No WebView and no separate competing models.

## Model API (data worker creates)

```kotlin
package dev.dshmobile.app.model

enum class ConnectionState { DISCONNECTED, CONNECTING, SYNCING, ONLINE, OFFLINE, REVOKED, INCOMPATIBLE }
data class Workspace(val id: String, val name: String, val canExecute: Boolean)
data class Preset(val id: String, val name: String)
data class SessionSummary(val id: String, val title: String, val workspaceId: String, val updatedAt: Long, val running: Boolean, val canExecute: Boolean)
data class ChatMessage(val id: String, val role: String, val text: String, val createdAt: Long, val requestId: String? = null, val provisional: Boolean = false)
data class SessionSnapshot(val session: SessionSummary, val messages: List<ChatMessage>, val cursor: Long, val hasMore: Boolean, val activity: String, val notice: String? = null)
// kind literals: create, send, cancel. Only send owns an optimistic user-message bubble.
data class PendingCommand(val requestId: String, val kind: String, val sessionId: String?, val text: String?, val status: String)
data class MobileCapabilities(
  val sessions: Boolean, val textPrompt: Boolean, val cancel: Boolean, val liveSnapshots: Boolean,
  val attachments: Boolean, val questions: Boolean, val approvals: Boolean, val push: Boolean
)
data class MobileState(
  val capabilities: MobileCapabilities? = null,
  val paired: Boolean = false,
  val hostName: String = "",
  val connection: ConnectionState = ConnectionState.DISCONNECTED,
  val workspaces: List<Workspace> = emptyList(),
  val presets: List<Preset> = emptyList(),
  val sessions: List<SessionSummary> = emptyList(),
  val sessionsTruncated: Boolean = false,
  val snapshot: SessionSnapshot? = null,
  val draft: String = "",
  val pending: PendingCommand? = null,
  val busy: Boolean = false,
  val error: String? = null,
  val lastSyncedAt: Long? = null,
  val demo: Boolean = false
)
```

DTOs may use @Serializable. Never parse unknown enum fields into fabricated valid behavior. Wire role/activity/status unknowns must produce safe fallback presentation and no unsafe actions. Lists default only where permitted; malformed auth/snapshot is a connection error, not an empty successful chat.

## Repository API (data worker creates)

`dev.dshmobile.app.data.MobileRepository` is an interface with:

- `val state: StateFlow<MobileState>`
- suspend `restore()`
- suspend `pair(invitationJson: String, deviceName: String)`
- suspend `refresh()`
- suspend `selectSession(sessionId: String)`
- suspend `createSession(workspaceId: String, presetId: String? = null)`
- suspend `updateDraft(text: String)`
- suspend `sendMessage(text: String)`
- suspend `cancelRun()`
- suspend `resolvePending()`
- suspend `abandonPending()` — explicit confirmed local escape from an unresolved receipt; clears pending and its send draft, never cancels or re-sends upstream work
- suspend `forget()` — local forget only, copy must not claim server revoke
- suspend `setForeground(active: Boolean)`
- `close()`

Factory `fun createMobileRepository(context: Context, scope: CoroutineScope): MobileRepository` in data package creates actual network repository. It receives no Activity and does not retain one. MainActivity/ViewModel owns scope; UI may create an AndroidViewModel as a small host. All errors are converted to state, not uncaught UI exceptions. Error strings must be safe, actionable and must not include invitation/credential/personal path/response dumps. Prefer stable error keys with UI localization (document keys as returned).

## Optional remote transport v0.2

Direct invitation v1 remains supported. Remote invitation v2 follows [RELAY_PROTOCOL.md](<RELAY_PROTOCOL.md>): trusted one-use import/host confirmation, then automatic foreground connection without a separate VPN/Tailscale app or manual host credentials. PC/DSH/connector and a reachable public relay must be running. This contract was planned for product version `0.2.0` / versionCode `2`; the current source release is `0.9.0` / versionCode `17`. Local JVM evidence is not native/public/physical-phone acceptance.

- Inner HTTPS uses the exact `h-<32hex>.dsh.invalid:443` authority through an ephemeral IPv4 loopback CONNECT proxy with a private random credential and no arbitrary destination/ordinary HTTP forwarding. Preserve certificate trust/date/hostname validation plus SPKI pin; no DNS or global proxy changes.
- Outer WSS has separate public trust and route access capabilities, no redirects, and bounded framing/queues/headers. Use the relay contract's guarded framing implementation; ordinary OkHttp WebSocket callbacks alone do not bound fragmented-message allocation.
- Validate and atomically encrypt per-device relay access with the device bearer. Retire bootstrap transport after pairing; missing/expired/corrupt permanent access must not downgrade to bootstrap. No capabilities in plaintext preferences, logs or UI diagnostics.
- Device access expiry (initially 365 days) requires explicit re-pair. Invitation deadline is at most 15 minutes. The setup TLS identity lasts 825 days and requires reviewed rotation, not silent regenerated trust.
- Transport close/timeout/lifecycle releases observation resources and transient quotas, not durable grants or DSH work. In app 0.9.0, an admitted send owns a separate bounded process-lifetime transport and survives Activity backgrounding. Foreground reconnect restores an authoritative snapshot and reconciles original receipts; no uncertain byte replay, offline auto-send or permanent background promise. Only a transport-proven network failure **before request headers are sent** may retry a prompt automatically: at most three attempts with the same requestId, 500/1500 ms backoff; no replay after headers, pending/uncertain receipts, TLS trust errors or process restore.

## Behavior

Restore encrypted paired-host record, draft per selected session and pending command before connecting. Store SPKI pin / optional trust certificate and endpoint with device secret encrypted via Keystore AES-GCM in private storage; exclude all from Android backups (manifest worker handles). Atomic local writes; do not store bearer in preferences/plaintext/logs. The repository marks ONLINE only after authenticated capabilities and authoritative state load, not just TCP success. Session navigation drains validated pages of 100 up to a total of 1000 sessions; reject repeated cursors/duplicate IDs, never silently discard nextCursor. If a further page remains, set sessionsTruncated=true and display the explicit first-1000 limit. This first-slice cap is distinct from bounded message-history paging. A normal load can add up to 50 supplemental rows beyond the 1000 date rows in memory. Subsequent ticks retain already loaded rows until a normal full reload replaces the window; the persistent index remains capped at 1000. Home polling waits at least five seconds after each completed request, performs only the first page with no transparent retry, and never starts background date paging. Thus steady Home polling uses at most 12 physical requests/minute (10% of the 120/min device budget, 108/min headroom before initial/manual loads or other features). Initial/manual full loads and their ordinary bounded transport retries are separate from that polling budget. Optional tick failures retain the last online state and back off at least 30 seconds, up to 60 seconds exponentially, or longer for Retry-After; authentication/TLS/retired-transport failures retain ordinary failure handling. Selection/background/close cancel ticks. A successful tick preserves loaded older rows and their last-known metadata; only a complete supplement may clear all active flags for an omitted row outside the fresh page. A missing or truncated supplement cannot imply completion. A normal full load may replace retained rows. Normal All/project views show active rows first; a nonblank trimmed search retains date-only grouping/order and has no Working section.

HTTPS certificate trust uses system CA or explicit invitation certificate trust anchor AND hostname/validity checks plus SPKI pin. No trust-all manager/HostnameVerifier. HTTP only BuildConfig.DEBUG and exact loopback, path-free base URL. Reject URL userinfo/query/fragment. Require pin for HTTPS. Use OkHttp event source with Authorization header. Background closes observation only. Foreground reconnect uses bounded backoff and new complete snapshot; errors display OFFLINE with stale state labelled. Never cancel host task on lifecycle disconnect.

Serialize send admissions; persist original requestId and text before network request. Do not blindly retry non-idempotent writes; query original receipt on failure. Retain pending across process death; uncertain state has resolve/check action, not new resend. Reconcile requestId from snapshot. No implicit offline queue/auto-send. Draft survives navigation/recreation; one unresolved mutation blocks new send until explicitly reconciled or consciously abandoned locally. A receipt 404 never automatically proves no execution. The escape action requires a warning to inspect the host first: it clears the local pending record and associated send draft, does not cancel existing host work and does not resend anything. This avoids permanently stranding the composer while preserving uncertainty. Cancellation also has its own ID/receipt and is not assumed completed until authoritative state agrees.

UI uses lifecycle-aware state collection and reports foreground via Lifecycle observer. String resources EN base + RU translation. Native chat-first drawer, new-chat project/preset sheet, pairing/import screen, settings/connection screen. Uploads and answers to agent questions exist with host plugin 0.7.0 and are shown only when the host publishes their capability flags. Features that are unavailable (push, agent action approvals, and any phone feature whose flag the host does not publish) remain absent or show a truthful unsupported notice, never placeholder enabled controls.
