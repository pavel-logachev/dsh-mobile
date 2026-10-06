# Background notifications — design and acceptance contract

Status: proposed capability, not implemented or advertised. Research checked on 2026-10-06. This document adds a design to the current development preview; it does not authorize deployment, source changes, new dependencies, live-profile changes or device tests.

## Decision and scope

Recommend a **phased hybrid** with one host-owned notification journal and several explicitly selected delivery modes:

1. **MVP: opt-in foreground monitoring**, one small authenticated notification SSE connection for every permitted project/chat, independent of the displayed conversation. Use a `specialUse` foreground service for this notification-only function, with an honest manifest subtype, a persistent notification and a Stop action. Require the owner to configure and test battery exemptions on the actual OnePlus. Do not use `dataSync` for indefinite monitoring or quietly keep the existing Activity socket alive.
2. **Preferred long-term mode: UnifiedPush**, using the non-Firebase ntfy Android distributor and an owner-managed ntfy server. The desktop sends RFC 8291 encrypted, content-free signals directly to the push endpoint. The existing opaque relay is unchanged. An encrypted minimal event envelope can display a generic alert without needing a second network request; authoritative sync still follows when permitted.
3. **Optional delayed fallback: periodic WorkManager**, with a 15-minute minimum interval and no maximum latency claim. It is reconciliation, not an instant-delivery substitute. Add it after the basic foreground slice, or alongside UnifiedPush if its permission/dependency cost is accepted.
4. No automatic mode escalation: missing push must not silently start a foreground service. Offer the user a choice; run at most one primary real-time delivery mode. All modes share dedupe and preferences.

The requested alerts are **answer finished** and **attention needed on the desktop**. Mobile approvals and question responses remain unsupported. Agent execution, tools and provider credentials remain on the PC. No GMS, Firebase (including embedded FCM distributors), telemetry, public default push account, third-party plaintext message content or raw DSH port exposure.

**Governing limitation:** without a privileged OS push path, no option guarantees instant delivery after force-stop, a powered-off phone, a disconnected VPN, disabled notifications, OEM network shutdown or a sleeping PC. UnifiedPush moves the persistent connection to a distributor; it does not remove this limitation. Background, Activity closed, ordinary process death, Recent-app swipe, Android Task Manager Stop and Settings Force stop are different states. The product must say which were actually tested. If “closed” includes force-stop and an absolute guarantee, the stated requirement cannot be met under these constraints.

## Existing implementation evidence

Read the project instructions and [Architecture](<ARCHITECTURE.md>), [Security](<SECURITY.md>), [Mobile protocol](<PROTOCOL.md>), [Relay protocol](<RELAY_PROTOCOL.md>) and [Plan](<PLAN.md>). Their background capability gate remains binding. The existing architecture's requirement for a configured/exercised background delivery path is not satisfied by this document: treating a visible user-enabled FGS as that path requires explicit owner acceptance of this proposed capability extension and the physical gate. The Architecture's older “optional FCM” future item is **not selected**: the owner's current no-Firebase constraint supersedes that suggestion; this task changes no other document.

| Evidence | Consequence for notifications |
| --- | --- |
| [Cordis plugin event bridge](<../host/src/plugin.ts>) subscribes globally to `session/event`, `api-session/status` and `agent/assistant-stream`, but each `subscribe(sessionId, ...)` filters to one session. | The existing global `session/event` seam is the right production source for **all sessions**, including work started on the PC and new sessions the phone has never opened. Add one lifecycle-owned global listener, not one full transcript observer per session or device. |
| [Adapter](<../host/src/dsh-adapter.ts>) consumes durable `turn/start`, `turn/end`, `approval/asked`, `approval/decided`, `tool/call` (`ask_user_question`) and matching `tool/result`; opening projections include `userQuestions.active`. `Transcript.snapshot()` chooses `unknown` before `waiting`, then running/idle. | Reuse inspected normalization rules, but build a separate minimal event reducer. Provisional assistant-stream `end`, `running:false`, command acceptance and `step/end` are **not** answer-completion proof. |
| [Synthetic rc.2 fixture](<../host/test/fixtures/rc2.ts>) has `turn/end.data.reason.kind = "completed"`, an event sequence and turn number. [Adapter tests](<../host/test/dsh-adapter.test.ts>) cover pending questions/approvals without exposing their private data. | There is evidence for these shapes, not a verified exhaustive outcome enum or lossless restart replay API. Validate against both exact allowlisted upstream versions (`0.2.0-rc.2`, `0.2.1-alpha.1`) in an isolated canary before advertising the capability. |
| [Adapter cold-read implementation](<../host/src/dsh-adapter.ts>) calls `follow(... assistantStream:true, maxMessages:100)`, reads only its opening frame, aborts and returns the iterator. Per-session `watch` defaults to 1-second refresh and 100-ms output throttle. | Never advance `follow` to a second frame merely to observe: it can promote a cold session. A fleet of full `watch` iterators would repeat controller listing, filesystem checks and transcript processing unnecessarily. |
| [Workspace source](<../host/src/workspace-source.ts>) uses the current registry Service, exact canonical path identity, status/revision rechecks, first 100 registrations, and archived-session filtering. [Adapter listing](<../host/src/dsh-adapter.ts>) excludes subagents and resolves cwd anew. | “All sessions” means all permitted top-level sessions in the current supported workspace scope, not hidden archives, subagents, arbitrary directories or registrations beyond the existing bound. Wildcard read grants include current/future supported projects. Notifications must obey the same current mapping and fail closed. |
| [Host server](<../host/src/server.ts>) exposes `/v1/sessions/{id}/events` replacement snapshots, not an all-session event journal. It has 3 streams/device, 32 total, periodic scope checks, a revoke hook and `push:false`. | Introduce a separate small notification feed and durable cursor. Do not retrofit a replay promise onto conversation SSE. `accepted` command receipts prove admission, not completed work. |
| [Host state](<../host/src/state.ts>) has SQLite transactions, device hashes/grants, runtime ownership and command receipts, but no notification journal. | Add metadata-only journal, per-device policy and producer watermarks in a later implementation. Runtime ownership remains mandatory. Delivery is at-least-once within retention, not exactly-once. |
| [Relay server](<../relay/src/server.ts>) authenticates outer capabilities and forwards binary TLS chunks; its HTTP surface is only health. [Relay contract](<RELAY_PROTOCOL.md>) specifies independent grants, fixed target and generation cleanup. | It cannot detect a finished turn or read push registration. Do not inspect TLS or infer events from traffic size/timing. The proposed host publisher needs **no relay wire change**. |
| [Android repository](<../android/app/src/main/java/dev/dshmobile/app/data/NetworkMobileRepository.kt>) and [Host API](<../android/app/src/main/java/dev/dshmobile/app/data/HostApi.kt>) observe only the selected chat; `setForeground(false)` cancels observation/reconnect and retires transport. | Add a separate application-owned notification coordinator and read-only transport owner. Do not flip the repository's UI-foreground flag or accidentally resume pending prompt/cancel delivery in a service/worker. Preserve IO-owned asynchronous socket cleanup. |
| [Settings](<../android/app/src/main/java/dev/dshmobile/app/ui/settings/SettingsScreen.kt>) currently explains unavailable notifications. [Build configuration](<../android/app/build.gradle.kts>) has minSdk 26 / targetSdk 36; [dependency catalog](<../android/gradle/libs.versions.toml>) has no WorkManager or UnifiedPush. [Source manifest](<../android/app/src/main/AndroidManifest.xml>) removes `ACCESS_NETWORK_STATE`. | This is a new capability, with deliberate dependencies, permission review and capability-gated UI, not a small switch on the existing SSE client. |

The already-generated [release manifest](<../android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml>) was also inspected: only `INTERNET`, `CAMERA` and the signature-level internal receiver permission were present. This was an existing build artifact, not a newly verified build. Source was changing concurrently during research; the observations above describe the inspected seams, not a frozen implementation revision. No live DSH/runtime storage or device was inspected.

## Android 14–16 governing constraints

### Foreground service type

Android 14 requires an appropriate manifest FGS type and its type-specific permission in addition to `FOREGROUND_SERVICE`; missing declarations can throw `SecurityException` or a missing-type exception. This applies to targetSdk 36. [Official types](https://developer.android.com/develop/background-work/services/fgs/service-types?hl=en), [version changes](https://developer.android.com/develop/background-work/services/fgs/changes?hl=en).

- **`dataSync`: unsuitable for always-on monitoring.** Android 15+, for apps targeting 35+, allows a total of six background hours in 24 hours shared by that app's `dataSync` services. Foreground interaction resets the timer. `onTimeout(int,int)` must stop promptly; ignoring it can crash the app. Launch from `BOOT_COMPLETED` is also prohibited for this type. Rotating services, adding another type, alarms or restarting the process is not a legitimate reset strategy. [Timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout?hl=en).
- **`remoteMessaging`: plausible for actual cross-device text-chat continuity, not blanket permission for arbitrary state alerts.** Its documented purpose is “Transfer text messages from one device to another” and continuity of messaging when switching devices; it has `FOREGROUND_SERVICE_REMOTE_MESSAGING` and no listed runtime prerequisite. A future service really synchronizing desktop conversations could justify it. The proposed notification-only feed transfers task-state metadata, not messages, so do **not** claim that the docs explicitly approve it. Prefer the honest type below rather than misclassifying the use case to escape a timeout.
- **`specialUse`: proposed notification-only type.** It covers valid user-facing FGS work not covered by other types; declare `FOREGROUND_SERVICE_SPECIAL_USE` and `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Proposed subtype: `User-enabled continuous monitoring of a paired local AI desktop for answer completion and approval/question attention; no platform push service is used; persistent notification with Stop control.` There is no documented six-hour cap for this type in the consulted Android 14–16 guidance. That is **not** an unlimited execution guarantee or a power exemption. Validate the manifest/start/stop behavior on APIs 34, 35 and 36. If the implementation becomes primarily text-message transfer, revisit the type instead of declaring both to avoid constraints.
- `shortService` (about three minutes), `connectedDevice`, media types, `systemExempted`, fake VPN/device-admin roles or acquiring exact-alarm permission for a type exemption are not appropriate substitutes.

Google Play's declaration/review of `specialUse` and restrictions on battery-exemption requests matter **only if Play distribution is introduced**. Current GitHub APK distribution has no Play Console approval gate. Android platform restrictions, required notifications and honest type selection still apply to a sideloaded APK. Do not equate “not on Play” with exemption from Android enforcement.

### Start, stop, jobs and power

- Android 12+ disallows ordinary FGS starts while backgrounded except defined exemptions. Start MVP monitoring from a visible Activity after an explicit enable action, call `startForeground` immediately within the platform startup deadline, and catch `ForegroundServiceStartNotAllowedException`/`SecurityException`. Do not promise automatic restart from an arbitrary broadcast or worker. A UnifiedPush broadcast is **not** an FCM high-priority exemption. [Start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start?hl=en).
- MVP has no boot receiver and requires manual restart after reboot. Later opt-in boot restoration must be separately tested for the selected type and after credential unlock; a `BOOT_COMPLETED` exemption is not permission to launch a forbidden type. Sticky process recovery is best-effort; never use restart loops to override an explicit Stop.
- On Android 16, WorkManager/JobScheduler jobs obey their quotas even while an FGS is running. Long-running WorkManager cannot be used to wrap an indefinite socket and sidestep these rules. [Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-all?hl=en).
- FGS improves process importance and avoids ordinary App Standby classification while running, but must not be sold as universal immunity to **deep Doze**, Battery Saver, Data Saver or OEM killing. Doze suspends network access and defers jobs; a user-granted partial battery exemption allows network/partial wake locks, but not exemption from every limit. A no-Firebase persistent socket needs explicit power configuration and physical validation. The ntfy claim of instant Doze delivery describes its configured distributor, not a guarantee for our app on arbitrary OEM builds. [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby?hl=en).
- **No exact alarms.** Remote events occur at unknown times. Repeating alarms are inexact, allow-while-idle alarms are throttled (official Doze guidance: no more than once per nine minutes/app), and do not confer an unlimited network budget. `SCHEDULE_EXACT_ALARM` is special access, not pre-granted to fresh installs targeting 33+; `USE_EXACT_ALARM` has narrow intended uses and Play policy limits. Neither makes remote polling an alarm-clock function. Quiet hours are evaluated on receipt/sync, not with exact timers. [Alarm guidance](https://developer.android.com/develop/background-work/services/alarms?hl=en).
- Android Task Manager **Stop** removes the whole app/FGS without a callback; Android's docs say existing scheduled jobs/alarms may still run. Settings **Force stop** is stronger: do not expect push or polling until the user launches again. Respect explicit in-app Stop without silently resurrecting monitoring. [User stopping FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping?hl=en).

### Notification permission

All alert modes add `POST_NOTIFICATIONS` and request it at runtime on Android 13+ **after** the user enables notifications and sees the rationale. On 26–32, check app/channel availability without that runtime dialog. Denial preserves chat use and displays “Notifications blocked” with a system-settings link; do not keep asking automatically.

An FGS can technically start without notification permission but must still supply its foreground notification; on 13+ denial hides it from the drawer while it remains visible in Task Manager. Product policy: do not enable notification-only continuous monitoring while alerts are denied, because it consumes power without delivering the requested value. [Official notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission?hl=en).

## Options matrix

Latency ranges below are engineering expectations **when the network/process is available**, not official SLAs or measured results. Cost depends on radio, signal, Tailscale and reconnects; measure rather than claim a fixed percentage.

| Option | Latency | Battery / visible behavior | OnePlus reliability | Privacy / operator metadata | New app permissions | Fit / effort |
| --- | --- | --- | --- | --- | --- | --- |
| **A. Opt-in FGS + one host SSE** | Normally seconds from durable event; interrupted until reconnection/power recovery. | One permanent low-importance notification; radio heartbeats and TLS/WSS/VPN overhead; relay already needs outer heartbeats. Avoid wake-lock held all day. | Best simple app-owned option **only after** battery/OEM configuration; still killable. Recent swipe behavior varies; force-stop defeats it. | Host sees authorized IDs/state; opaque relay sees IPs, route/access binding, connection life, sizes/timing, possible TLS metadata, not SSE/content/bearer. Direct route has no push VPS. | `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (34+ declaration); recommend `ACCESS_NETWORK_STATE` for bounded reconnection. Optional later `WAKE_LOCK` for short bounded processing, not permanent. | Moderate, reuses pinned HTTPS/relay transport but needs global producer/journal and new coordinator. Direct LAN/Tailscale supported wherever host reachable; pure LAN cannot work away from LAN. |
| **B. Periodic WorkManager polling** | Minimum repeat interval 15 min; nominal scheduling can find a recent event within a cycle, but delay can be hours/unbounded under Doze, constraints/OEM kill/offline. No “within 15 minutes” promise. | No persistent notification for ordinary workers. Short metadata-only calls; repeated mobile TLS/tunnel setup costs energy even with no events. Do not fetch all transcripts. | Survives ordinary process death/reboot scheduling; OEM restriction/force-stop still blocks. Not an urgent approval solution. | Same pinned reads as A; relay learns polling cadence and traffic volume. No new cloud. | `POST_NOTIFICATIONS`; WorkManager normally merges `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`. Audit exact pinned release/merged APK; current explicit removal of network permission must be reconsidered. | Low incremental Android effort after common producer exists; add approved WorkManager dependency. Direct-only compatible but only runs usefully when that direct route/VPN is available. |
| **C. UnifiedPush + ntfy** | Normally seconds while distributor runs; delay during distributor/server outage/OEM sleep. Generic encrypted envelope may show before host sync; pure wake-only payload must wait for host fetch. | One shared distributor connection amortized across apps. ntfy non-Firebase instant delivery uses its own FGS/permanent notification. This can reduce DSH Mobile idle cost, not make phone cost zero. | Configure ntfy **and**, where needed, DSH Mobile and Tailscale; killing distributor breaks delivery. Client wake/fetch also has Android limits. Requires physical verification. | Push server/VPS sees sender/phone IPs, endpoint/topic, timing, size, subscription linkage; encrypted body hidden from server **and distributor**. Plain ping discloses activity timing but no chat text. Never put titles/questions/answers or bearer in headers/topic/URL. | `POST_NOTIFICATIONS`; connector-specific merged components/permissions must be audited. For bounded sync fallback, WorkManager permissions as B. No long-lived DSH FGS needed for encrypted generic display; a deliberately short sync FGS would require its own valid type/start basis. | Highest integration effort: distributor UX, RFC 8291/VAPID, endpoint lifecycle, SSRF defenses, challenge, outbox and service setup. Direct users need no opaque relay, but need some reachable push server; a private ntfy server can run on LAN/Tailscale if reachable by both peers. A VPS alone is **not** an Android distributor: the ntfy app is still installed. |
| **D. Hybrid (recommended)** | C or A primary latency; B only delayed recovery. Mode switches catch up from one journal. | One primary socket owner, plus optional infrequent catch-up. Never run A and C permanently just because both exist. Show mode/degradation honestly. | Adds recovery paths, not an OS/OEM bypass. Safe handling of stop/denial beats aggressive auto-restarts. | Same boundaries; operator can learn correlation across relay and push if both are on the same VPS. No content cloud. | Union of selected implementation permissions, disclosed before enabling; permission minimum reviewed per phase. | Highest total, delivered incrementally. Direct users can select A/B without distributor or relay; C is optional. |

For OnePlus, [Don't kill my app](https://dontkillmyapp.com/oneplus) reports optimization resets, aggressive Recent-app clearing and sleep-standby network shutdown. It recommends disabling optimization and locking Recent apps but explicitly says this is not 100%. It is community evidence, not a model/OxygenOS-specific official contract. Provide links and current system settings entry points; do not automate changing system policy. Test the owner's actual model/OS before prescribing exact menu labels.

## Recommended topology and phased rollout

```
DSH global durable session events + cold-safe reconciliation
           |
Host notification producer / per-device grants+policy / SQLite metadata journal
           |                        |
Pinned host notification SSE/GET     Host RFC8291 Web Push publisher
           |                        | outbound HTTPS only
Direct HTTPS or existing opaque     Owner's ntfy push server (may share VPS,
relay carrying inner TLS bytes      but is a separate service/trust boundary)
           |                        |
Android notification coordinator   ntfy Android distributor -> connector
           \________________________/
                 local dedupe, channels, quiet policy
```

### Phase 0 — feasibility gate (1–2 engineer-days)

Confirm owner choices below; inspect exact upstream terminal reasons/projection behavior in an isolated, authorized DSH canary. Implement a throwaway proof **only in a later authorized task**, not during this document task: specialUse manifest/start/stop on API 36, two-session global hooks, screen-off connection behavior with/without exemption and OnePlus controls. Abort the FGS claim if this gate fails; proceed to tested distributor setup instead. No live production profile or new public service is implied.

### Phase 1 — MVP (8–12 additional engineer-days)

Common global producer, durable metadata journal/dedupe, read-grant filtering, capabilities and settings; foreground monitoring; completion and attention alerts; project/chat inheritance, quiet mode; generic lock-screen copy; Stop, revocation, offline/resync and test action. Works on direct HTTPS/Tailscale and unchanged relay transport. No push server/distributor, WorkManager, exact alarms, boot auto-start, notification replies, titles/previews in push, transcripts in the journal or automatic PC wake.

Acceptance label: **“Foreground monitoring: enabled; background delivery tested with the listed battery settings on this build/device.”** Not “always works when closed.” The owner must accept a persistent notification and power configuration. Without that acceptance, A is not an acceptable default; start with Phase 2, with a larger MVP.

### Phase 2 — UnifiedPush and delayed recovery (8–12 additional engineer-days)

Add a pinned current connector/Web Push implementation and non-Firebase ntfy distribution instructions, server endpoint validation, VAPID, challenge activation, outbox, encrypted generic event envelope, endpoint renewal/removal and optional WorkManager catch-up. Prefer self-hosted ntfy; optional external providers require explicit user acceptance of metadata disclosure and must not use Firebase paths. Verify at least two non-FCM distributors as recommended by UnifiedPush; document which versions are supported. Then recommend UnifiedPush when configured and tested; retain A as explicit direct-only fallback, B as clearly delayed mode.

### Phase 3 — operational hardening (3–5 additional engineer-days)

Physical OnePlus overnight/roaming, VPN/tunnel outages, OEM update retest, restart/gap recovery, quotas, dependency/manifest and security review. Optional boot restoration and richer encrypted previews are separate decisions, not needed for acceptance of generic alerts.

Planning estimate: approximately **20–31 engineer-days including gates/hardening**, normally 4–6 calendar weeks for one engineer with device/operator access; phases can overlap with test setup. No existing test result proves this estimate or delivery reliability. A UnifiedPush-first MVP is roughly 12–18 engineer-days plus physical validation. Relay code effort is zero for the recommended host-publish topology; ntfy deployment/operator maintenance is additional work and requires permission.

## Host producer: all-session semantics

Add a narrow adapter seam in a later implementation, conceptually `observeNotificationSources(signal): AsyncIterable<NormalizedNotificationSource>`; the Cordis bridge subscribes **once globally** to `session/event` and status. This is a proposed internal seam, not an invented upstream RPC. Host startup/shutdown owns its disposer. Do not subscribe to assistant text chunks for notifications.

1. Install the listener before baseline discovery. Buffer bounded event envelopes while enumerating current supported top-level sessions; preserve session event `seq` order. Use `listSessions`/fresh workspace mapping for eligibility, not paths or cwd supplied by an event/client. Recheck registry revision after filesystem awaits. Unknown session IDs trigger bounded fresh discovery, including chats created on the PC.
2. Store a producer watermark and minimal running/turn/pending-attention identities per session, independent of phone stream/UI lifetime. Dedupe source events by `(sessionId, durable seq, normalized kind)`. A durable PC-origin event is treated exactly like a mobile-origin event.
3. **Completion:** accept only inspected `turn/end` with `reason.kind:"completed"` and a known turn identity. Resolve fresh cold-safe opening evidence and verify a committed visible assistant answer from that turn, no `unknown`/surface ambiguity and no pending waiting attributable to that turn. Existing public `HostSnapshot` messages do not retain turn IDs, so the proposed internal normalizer must derive this proof from inspected opening records/surface replacements; do not infer turn attribution from timestamp or the last message alone. An assistant message can be an intermediate tool step; assistant-stream settlement is not turn completion. Cancellation/error/unsupported end reason must not use “Answer finished.” A later queued turn starting does not invalidate the previous completed turn if its durable terminal event/answer can still be proved. If the bounded cut omits that proof, suppress a completion rather than guess from `running:false`.
4. **Attention:** `approval/asked` keyed by approval ID; `approval/decided` clears it. `ask_user_question` keyed by call ID; matching `tool/result` and authoritative `userQuestions.active` settle it. Include pending question projections on initial/recovery snapshots. Do not retain question text, tool arguments or approval reasons. **Do not use public `activity:waiting` as sufficient evidence of currently blocked input:** the existing synthetic test deliberately maps `userQuestions.active.state:"continued"` to waiting. Exact still-actionable projection/approval semantics require canary validation. Emit attention only for a proved live unresolved approval or genuinely pending question episode; ambiguous/continued-only state remains a generic in-app desktop notice with no new alert. Never infer a safe approval action from it.
5. Coalesce overlapping pending reasons into one attention episode per session. Emit entry to waiting and a new episode after it was fully cleared; additional reasons update the same episode silently. Emit `attention-cleared` on authoritative resolution so clients remove stale alerts. No timer-based repeated nagging. A startup with a still-pending request may produce one current attention alert; historical completed turns do not generate a startup storm.
6. Global callbacks observe the sequence of every durable `session/event`, including ignored kinds, so filtering non-notification events cannot create artificial sequence gaps. Retain only allowed type/sequence/time/IDs/reason kind; track ignored-event sequence advancement without retaining their bodies. Known ignorable kinds advance the watermark; unknown required semantics degrade coverage. Expensive source checks and SQLite work occur in a serialized bounded queue, not unbounded promises in the Cordis callback. Startup completion baselines and live buffered events have a declared cut: anything at/before that baseline is not a newly completed answer.
7. A reconciliation pass every 60 seconds rechecks discovery/scope and known running/waiting/dirty sessions, with at most four concurrent cold-safe reads and a ten-second deadline per read. Avoid a full transcript refresh for every archived/idle chat every second. New IDs seen in events are checked promptly (target ≤2 seconds under normal load). Initial discovery handles all sessions the upstream list supplies in the existing workspace scope; do not arbitrarily cap the phone's current session-list pagination and call that all-session coverage.
8. An event `seq` gap, source queue overflow, missing registry service or incompatible payload marks **producer coverage degraded**. Reconcile from opening records/projections; never take a second follow frame. Recover exact missed terminal events only if the inspected opening cut contains the contiguous required evidence. There is no inspected unlimited durable replay contract in this code. If unavailable, report a coverage gap, rebaseline without fabricated completions, and recover currently pending attention only. Host/plugin downtime can therefore miss a short completed turn; adding verified upstream paging later could strengthen recovery.

Suggested producer bounds: max 4,096 pending minimal source events and 1,024 distinct dirty session IDs, ≤1 KiB/envelope, bounded before retaining data; active minimal session state ≤10,000 records and five MiB serialized metadata. Process all listed sessions in batches; if this active-state bound is exceeded, stop claiming complete coverage, surface `degraded`, and require an owner-reviewed scale increase rather than silently monitor only the most recent sessions. Unknown-session discovery coalesces per ID; no more than one full list request/second. Periodic filesystem/registry checks retain existing fresh authorization semantics, not a cross-request cwd cache.

## Proposed Mobile API v1 additions

These routes/events are **new proposal**, not current endpoints. Keep `/v1`, camelCase, epoch milliseconds, no-store headers, safe error envelopes, unknown response-field tolerance and current HTTPS/hostname/date/SPKI pin verification. No raw upstream events or host paths. All additions are inside the existing end-to-end TLS, including through relay. Existing safe error envelope remains; map unavailable/initializing producer to 503 `unavailable`, absent push support to 409 `unsupported_capability`, malformed/oversize bodies to 400 `invalid_request` / 413 `payload_too_large`, auth failure to 401, hidden-scope target to 404, stale policy/registration request to 409 `conflict`, stale state-generation to 409 `resync_required`, limits to 429 `rate_limited`. These are additive proposed safe codes, not errors currently exposed by every route.

### Capability negotiation

Extend `GET /v1/capabilities` without changing existing meanings:

```json
{
  "capabilities": {"push": false, "notifications": true},
  "notificationCapabilities": {
    "version": 1,
    "kinds": ["answer-finished", "attention-needed"],
    "feed": true,
    "unifiedPush": false,
    "payloadMode": "generic-encrypted",
    "coverage": "ready",
    "retentionMs": 604800000,
    "vapidPublicKey": null
  }
}
```

Show `notifications:true` only after the implementation is supported and the producer has established its first valid baseline/journal; afterward keep the capability present while exposing temporary loss of coverage separately. Coverage becomes `degraded` when complete source coverage is unproven; return a safe `coverageReason` enum (`source-gap`, `source-unavailable`, `capacity`, `incompatible`) without session/private details. During startup use `coverage:"initializing"` and `notifications:false` until that first baseline. Keep existing `push:false` in Phase 1. In Phase 2, `push:true`/`unifiedPush:true` means host publishing is configured and available, **not** that this device is registered or OS alerts allowed. `vapidPublicKey` is a validated uncompressed P-256 public key in canonical base64url when configured, null otherwise.

### Settings resource (one per authenticated device)

- `GET /v1/notification-settings` → 200 current resource; no push endpoint/key secrets returned.
- `PUT /v1/notification-settings` → 200 full replacement using `expectedRevision`; initial revision 0, increase by one on changed policy. Identical policy retry at the current/previous expected revision returns the same resulting resource; a different stale replacement → 409 `conflict`. This is notification policy only, outside the prompt command ledger. Client reconciles on a lost response, never replays a prompt.

```json
{
  "expectedRevision": 0,
  "enabled": true,
  "defaults": {"answerFinished": true, "attentionNeeded": true},
  "projects": [
    {"workspaceId": "alpha", "answerFinished": true, "attentionNeeded": true}
  ],
  "chats": [
    {"sessionId": "session-example", "answerFinished": false, "attentionNeeded": true}
  ]
}
```

Response replaces `expectedRevision` with `revision` and adds safe `pushState:"absent"|"pending"|"active"|"unavailable"` and `coverage`. Overrides are complete pairs of booleans; removing an override means inherit. Resolution: device master off > chat override > project override > defaults. A mute is not a read/execute grant. Unknown properties/duplicate IDs, invalid booleans or arrays are rejected. Up to 100 project overrides and 256 chat overrides, 32-KiB settings body within existing 64-KiB global ceiling. Validate project IDs/current grants and session mapping at update; reject unauthorized targets with hidden 404. Archived/stale overrides never authorize delivery and may be removed in a subsequent sync. Newly supported projects inherit defaults only within granted read scope.

Quiet schedule/temporary quiet and content-visibility preference are **phone-local** in encrypted notification state; they do not authorize the host to send less safely filtered data. The host master/filters avoid unnecessary fan-out/wakes; the phone repeats filters before display. A local mute applies immediately even when the host is offline; show unsynchronized server settings and reconcile with GET/revision on return.

### Metadata journal and events

Persist only notification metadata and delivery cursors in the host state DB. No transcript/title/answer/tool text cache. Per-device fan-out is a transaction with source dedupe/watermark, after current grant/source validation. First settings enable creates a journal epoch and a baseline cursor; old completions are not retroactively sent.

```ts
type NotificationEvent = {
  version: 1;
  eventId: string;       // random UUID persisted, stable across retries
  sequence: number;     // safe nonnegative integer, per device+epoch
  occurredAt: number;    // upstream event time; never used as cursor ordering
  expiresAt: number;
  workspaceId: string;  // existing opaque IDs, only inside inner TLS/encryption
  sessionId: string;
  kind: 'answer-finished' | 'attention-needed' | 'attention-cleared';
  sourceSeq: number;    // proof event seq, or reconciled opening cut >= -1
  turn?: number;        // validated safe integer where established
  attentionId?: string; // host UUID per waiting episode
};
type NotificationPage = {
  version: 1;
  epoch: string;         // UUID, stable across ordinary plugin restarts
  stateGeneration: number; // safe integer; ties replay cut to pending-state snapshot
  items: NotificationEvent[];
  nextCursor: string;
  hasMore: boolean;
  resetRequired: boolean;
  coverage: 'ready' | 'initializing' | 'degraded';
};
```

No question ID, private reason or execution permission is needed on the phone. API ID strings remain bounded by the current 256-byte segment contract; event/attention IDs are UUIDs; number fields must be finite safe integers, timestamps nonnegative. `sourceSeq` is a proof-event seq ≥0, or a reconciled opening cut ≥-1 for pending state with no retained initiating event; -1 is never completion proof. Retained host source IDs require the same bounded validation before allocation. New query parameters above require explicit route-specific allowlisting in the existing server parser; do not relax it globally. Notification control bodies (confirmation/test/acknowledgement) are ≤4 KiB and nesting depth ≤8; allowlist fields and reject duplicate query keys. `attention-cleared` has the same attentionId; it is control metadata, never a fresh audible alert. `answer-finished` expires for audible presentation after 24 hours; attention after 24 hours unless still authoritatively pending, in which case a fresh reconciliation state can display it silently. Seven-day journal retention is recovery capacity, not permission to announce a week's stale alerts.

- `GET /v1/notification-events?after=<cursor>&limit=50` → 200 `NotificationPage`; limit 1–100. Missing `after` returns **no historical completions**, the current head cursor and `resetRequired:true`; client then obtains pending state below. Old/pruned/wrong-epoch cursor returns the same safe reset semantics, not guessed replay. Malformed/MAC-failed/other-device cursor → 400 `invalid_request`. Filter current read grants/workspace mapping/archives **before** serialization; scanning skips removed-scope entries and advances safely. Page scan/response is bounded to 400 examined journal entries; when no visible entries fit the scan budget, return an empty page with `hasMore:true` and an advanced cursor, not an unbounded scan.
- `GET /v1/notification-state?generation=<safe-integer>&cursor=<optional>&limit=50` → 200 `{version:1,stateGeneration,items:[{workspaceId,sessionId,attentionId,sourceSeq,occurredAt}],nextCursor:null|string,coverage}` for **currently** pending permitted waiting episodes. No completed history. Limit 1–100; snapshot-bound pagination cursor expires in five minutes, ≤128 characters. Filter again on each page. This resource repairs stale attention after reset, restart or a missed clear. On reset read an events page/head and its `stateGeneration`, enumerate state at that generation, then replay from that saved head cursor; do not advance to a newer head after enumeration or lose completions that occurred in between. A generation mismatch at first read or change during paging → 409 `resync_required`, restart the bounded head/state sequence. After three unstable attempts retain the old cursor, show degraded sync and retry later. Journal capture/stateGeneration must be transactionally consistent; normal new completion-only entries do not require changing pending-state generation. Up to 10,000 current attention records within producer bounds; client stores at most 1,000 and shows a safe overflow summary if exceeded, never falsely labels full history synced.
- `GET /v1/notification-events/stream?after=<cursor>` → SSE over one logical host connection. Initial `event: notification-page` contains the same page envelope (≤100 events); repeat bounded pages until caught up. Subsequent `event: notification` contains one event plus its cursor; `event: resync-required` contains `{version:1,coverage}` when a journal gap/epoch change occurs, then closes. `event: coverage` carries safe coverage changes. Send heartbeat comments at ≤20 seconds, reuse current 15-second default. SSE `id` may be the validated cursor; reconnect explicitly sends `after`, not an implicit conversation-stream Last-Event-ID promise. If both `after` and `Last-Event-ID` are supplied they must agree, otherwise 400. Cursor is metadata, never a bearer.
- `POST /v1/notification-tests` body `{requestId:<UUID>,delivery:"feed"|"unifiedPush"}` → 202 `{requestId,state:"queued"}` for a synthetic content-free test signal, not a fake answer event. Persist device/requestId+payload dedupe for 24 hours (max five test records/device); changed reuse → 409. A feed test uses `event:test` with `{version:1,requestId,expiresAt}`, not `NotificationEvent`; push uses encrypted `{version:1,type:"test",requestId,expiresAt}`. Neither enters the completion/attention journal. `GET /v1/notification-tests/{requestId}` → `{requestId,state:"queued"|"sent"|"received"|"failed",updatedAt}` (own device only). `POST /v1/notification-tests/{requestId}/acknowledgements` with empty JSON → 204 after phone receipt; received does not mean Android displayed a notification. UI additionally reports local permission/channel state and asks the user whether the test appeared. Test TTL five minutes.

Cursor format: canonical base64url without padding of `{v:1,e:<epoch>,s:<sequence>,m:<MAC>}` encoded as compact JSON, ≤256 ASCII characters. HMAC-SHA256 covers version, epoch, authenticated device ID and sequence with a persisted independent host cursor key. Require exact schema, canonical encoding, safe integer and constant-time MAC comparison. The key never leaves private state. Cursor does not confer authorization. Validate the MAC before classifying a cursor as a recoverable epoch/retention reset. Accept initial sequence -1 only for the empty per-device journal sentinel. Next cursor advances only through a consistent transaction cut; sequences order ingestion, including old event timestamps. Do not regress the phone's persisted cursor on an out-of-order callback. Snapshot pagination for current state uses separate device-bound opaque tokens with a 1,024-token global cap.

Journal bounds: retain seven days **and** at most 2,000 events/device (whichever expires first), max 64 notification-enabled devices/host (existing relay-mode active-device cap remains 16), per-event serialized metadata ≤1 KiB, feed JSON/SSE frame ≤128 KiB. Cursor/watermark survives a normal restart; state reset/restore that can reuse sequence creates a new epoch. Dedupe source keys remain until covered by persisted contiguous source watermarks; active attention identity state remains until clear/rebaseline. Queue overflow/pruning produces reset/coverage notices, never a silent lossless claim.

### Authentication, revocation and flow control

Every route above requires the current `Authorization: Bearer <deviceToken>` inside pinned TLS. No notification credential can execute prompts or approve tools. No tokens in queries/SSE IDs/logs. Existing no-Origin/no-Sec-Fetch/no-permissive-CORS rule remains. Direct-only and relay devices use the same inner API. Current read grant/source membership/archives apply at admission, fan-out, every returned page and before a push; execute permission is not required to receive read-authorized alerts. Recheck scope on streams at least each second (plus source I/O), observe local CLI grant changes, and stop on revoke. Grant narrowing removes pending journal/outbox entries from newly unauthorized scope. Local forget stops delivery and deletes keys/cursors immediately but still does not prove remote revoke when offline.

Recommended additional bounds, enforced before allocation and in addition to existing server limits:

| Boundary | Proposed limit / handling |
| --- | --- |
| Notification stream | 1/device; counts against current 3/device and 32 total host stream caps. A foreground conversation stream can coexist. Slow writer >5 s or output queue >128 KiB + one bounded frame: close and resync. |
| Notification GETs / stream opens | 60 requests/min/device within existing 120 total; stream opens 6/min/device. `429` with `Retry-After:60`. |
| Policy/registration changes | 6/min/device; test 1/min and 5/day/device. All limiter maps bounded to 1,024 active device keys, expired keys pruned. |
| Source emissions | 30 journal alert/control entries/min/session, 120/min/device, 1,000/min/host. Beyond limit coalesce attention state and set reset/coverage gap; never discard a clear while keeping false “waiting” as authoritative. Recovery uses current-state sync. |
| Android processing | One serialized sync per host, up to four pages/512 KiB accumulated metadata per background pass (128 KiB/page ceiling) or 30-second deadline; persist cursor per fully applied page and schedule later catch-up if `hasMore`. No unbounded reconnect/page loop. |
| Reconnect | Jittered 1, 2, 4, 8, 16, 30 seconds then cap at 60 seconds while primary mode enabled; respect Retry-After and network availability. After prolonged failure show safe offline state, use max 5-minute retry interval. TLS/pin/credential error stops retries until review. |

Display is idempotent: Android atomically applies event+cursor to local notification state, uses `(paired host identity, epoch, eventId)` for ingestion dedupe, persists at most 2,000 processed IDs/seven days and a stable notification tag based on session+kind (no hash-only collision assumption). Process duplicate feed/poll/push events without extra sound; attention updates/clear use attentionId. Stable platform tag means a crash around `notify()` replaces the same alert; set `onlyAlertOnce`. This is best-effort user-visible dedupe, **not exactly-once OS display**.

## UnifiedPush registration and publication (Phase 2)

### Parties and privacy

The **desktop host is the application server/publisher**. ntfy server is the push provider, ntfy Android app is the distributor, and DSH Mobile contains only the connector. Self-hosting ntfy on the relay VPS is convenient but is a separate service. No Matrix gateway, browser subscription or custom distributor is necessary. [UnifiedPush introduction](https://unifiedpush.org/developers/intro/), [current connector](https://unifiedpush.org/kdoc/connector/), [Android spec AND_3.1.0](https://unifiedpush.org/developers/spec/android/), [ntfy phone setup](https://docs.ntfy.sh/subscribe/phone/#unifiedpush).

Host publishes directly to a validated HTTPS endpoint; the PC needs outbound reachability, not inbound public exposure. A direct-only host can therefore use UnifiedPush without our relay. If no VPS/external push service is wanted, run ntfy privately on LAN or Tailscale: phone distributor and host must both reach it, and mobile-data use requires an active tailnet route. Push can arrive through a public push server while a private host route is down; pure wake then cannot sync. An encrypted minimal event can still show a generic alert. Opening the chat still needs direct/VPN/relay host reachability.

Use a non-Firebase ntfy flavor and self-hosted server with no Firebase key and no upstream/APNS forwarding. UnifiedPush's embedded FCM fallback and Google-based distributors are excluded even if the embedded library contains no proprietary blob. For ntfy publication explicitly set `X-UnifiedPush:1` and `Firebase:no` through the ntfy-specific publisher adapter. Do not rely only on selecting a distributor name. [ntfy instant delivery/flavors](https://docs.ntfy.sh/subscribe/phone/#instant-delivery), [publish flag](https://docs.ntfy.sh/publish/#unifiedpush), [configuration](https://docs.ntfy.sh/config/).

RFC 8291 `aes128gcm` protects body content, not HTTP headers or timing. Generate keys with the current connector and use a reviewed standards-compliant Web Push library; no hand-written crypto or draft `aesgcm`. Public endpoint/topics are capability secrets: no logging/full URL in UI/diagnostics. A VPS operator can associate sender and receiver, timing, size, random endpoint and service use; same-operator relay/push correlation is possible. The distributor can identify that DSH Mobile receives notifications, but cannot decrypt the application envelope. Host and phone naturally see authorized metadata. [RFC 8291](https://www.rfc-editor.org/info/rfc8291/).

Default push payload has **no chat text, titles, question/approval reasons or tool arguments**, even inside encryption. The primary form is an encrypted envelope, not plaintext provider headers:

```json
{
  "version": 1,
  "type": "notifications-available",
  "epoch": "<UUID>",
  "cursor": "<device-bound-cursor>",
  "events": []
}
```

`events` may carry up to eight exact `NotificationEvent` records for immediate generic display; if the encrypted body would exceed 4,096 bytes, send a smaller envelope or only the wake signal. Host never truncates JSON. Plaintext before encryption ≤3,000 bytes, one RFC 8291 record, encrypted body ≤4,096 bytes; pad to a fixed size where practical within this cap. The encrypted envelope's cursor is a **sync hint**, not a committed phone cursor: a batch may omit intervening events. Only authenticated journal pages advance the durable replay cursor; push eventIds can be ingested/deduped separately. A pure wake configuration uses empty `events` and must disclose delayed notification display when sync is blocked.

A malicious provider cannot forge a valid encrypted host payload without the auth secret but can delay/replay/drop it. Reject wrong epoch/version, malformed/oversize/expired events, decryption failure and unregistered instance; dedupe. A new/unconfirmed epoch is a sync hint only: verify it over pinned host TLS before rendering its events; do not let an old replay switch the phone's epoch. On sign-out clear connector keys/registration so an in-flight revoked push is ignored. After host revocation an already-sent event can still arrive before the phone learns revocation; content-free payload limits exposure, and no new fetch succeeds. No protocol can recall bytes already delivered.

### Proposed registration routes

- `PUT /v1/push-registration` body `{requestId:<UUID>,endpoint:<HTTPS URL>,keys:{p256dh:<base64url>,auth:<base64url>}}` → 202 `{registrationId:<UUID>,state:"pending",expiresAt:<ms>}`. One active and one pending registration/device. Same requestId/canonical body returns original result (24-hour idempotency record, max 32/device; return 429 instead of evicting an unexpired proof); changed reuse → 409. Validated `p256dh` decodes to a 65-byte uncompressed on-curve P-256 key, `auth` to 16 bytes. Endpoint ≤1,000 UTF-8 bytes, whole body ≤4 KiB. No arbitrary headers/passwords submitted by the phone. Endpoint is a capability URL, not a query bearer for host auth.
- Host sends exactly one encrypted challenge `{version:1,type:"registration-challenge",registrationId,challenge:<32-byte random base64url>,expiresAt}` with five-minute expiry. Store challenge digest only. No real event delivery to an unconfirmed registration.
- `POST /v1/push-registration/confirm` body `{registrationId,challenge}` → 200 `{registrationId,state:"active",expiresAt}`. Current bearer, same device, matching unexpired digest, one-use activation transaction. Identical activation replay returns active result; mismatched/expired → safe 400. Store registration for 90 days and renew through explicit app-start registration; endpoint replacement must pass a new challenge before atomic activation and old registration cleanup.
- `GET /v1/push-registration` → 200 `{registrationId:null|string,state:"absent"|"pending"|"active"|"unavailable",expiresAt:null|number,lastSuccessAt:null|number}`; no endpoint/keys/challenge. Distributor package/server origin are safe locally chosen UI metadata, not server proof of non-FCM delivery.
- `DELETE /v1/push-registration` → 204 idempotent cleanup of own active/pending registration, keys and outbox. Disabling UnifiedPush unregisters with the distributor too. Device revoke cascades cleanup transactionally; grant narrowing prunes unauthorized pending events.

Persist endpoint/auth secret under owner-only host protection because publication needs them: unlike a bearer hash, these cannot be only hashed. Keep endpoint/auth/VAPID private key in encrypted host private state using an independently protected wrapping key; the normal SQLite DB stores only registration ID/binding/status and encrypted secret blobs. Decide Windows DPAPI versus operator-supplied wrapping key during implementation. Never commit secrets or reuse connector/device bearer as publisher credentials. Phone connector state/keys and journal dedupe are private, excluded from backup/transfer alongside current Keystore state. Tink used by the current connector is cryptographic library code, **not Google Play services**; audit transitive dependencies rather than reject/accept by vendor name.

### Endpoint validation / outbound safety

A paired device is not permitted to make the Windows host an arbitrary URL fetcher. Host-local configuration lists allowed push origins and optionally exact private destinations (for owner-managed LAN/Tailscale ntfy). Default rejects unconfigured origins, userinfo, fragments, cleartext, noncanonical URLs, redirects and credentials in arbitrary headers. A provider capability path/query may be required by standards and is treated as a secret; no generic ban that breaks valid provider endpoints, no logging of either.

At each publish/challenge resolve and validate **all** candidate A/AAAA addresses, reject loopback, link-local/metadata, multicast, unspecified and non-global IPs by default. Only exact operator allowlisted private origins/addresses may bypass non-global rejection; never a broad mobile-requested CIDR. Bind the actual connection to an approved resolved address while preserving TLS hostname/SNI and certificate validation; prevent DNS rebinding between check and connect. Disable redirects/proxy inheritance to unreviewed targets. Challenge proves receipt, **not** that a destination is safe. Never probe an endpoint before URL/origin/IP authorization. This follows [UnifiedPush registration and SSRF guidance](https://unifiedpush.org/developers/intro/#server-security).

For private ntfy auth, scope separate subscriber and publisher accounts with ACLs to random `up*` topics as described in [ntfy access-control examples](https://docs.ntfy.sh/config/#example-unifiedpush). The host-local publisher credential is optional per allowed origin, never accepted from a phone or sent to another origin. ntfy access tokens currently inherit the account's full rights: least privilege requires a separate account/ACL, not an assumed per-token scope. Restrict this publisher to permitted push resource paths; no admin operations. Validate selected ntfy version's handling of standard Web Push headers and capability endpoints in an isolated compatibility test; self-hosting is not evidence of correct RFC support.

### Outbox and rate limits

Fan-out writes journal first and a coalesced per-device wake outbox in the same transaction. Up to 64 pending device wakes/host, one in-flight publication/device and four globally. Coalesce for two seconds; maximum six publications/min/device and 120/min/host; merging a completion into a wake must not lose its journal event. Web Push uses `TTL:86400`, fixed `Urgency:normal` and per-registration collapse `Topic` (≤32 URL-safe characters, no chat identifiers). The provider can still see timing; fixed urgency avoids leaking attention versus completion in headers. Use VAPID if required/supported, with stable host-local keys and safe operator contact, no personal contact auto-export.

Ten-second connect/request deadline; response body ≤4 KiB and never logged. Provider 2xx means accepted, **not phone received/displayed**. Retry transport failure/5xx/429 with bounded jitter (5 s, 30 s, 2 min, 10 min, 30 min; respect bounded Retry-After), never more than ten attempts or beyond 24-hour expiry. 404/410 invalidates registration; 401/403 marks unavailable for user/operator review; no infinite auth retry. New events coalesce into the pending wake without an unbounded queue. On restart recheck registration expiry/current grants before republishing. Limit overruns do not delete journal metadata: sync/catch-up remains possible. No provider failure stops DSH work.

The current relay needs nothing for this design. If the owner insists on publishing **through** the relay rather than direct outbound host HTTPS, a later separately versioned authenticated control-plane operation would need to carry only validated destination plus opaque encrypted payload, with its own SSRF/rate/storage review. Relay would learn push endpoint/activity and additional mapping. It still could not discover events by itself. Building this gateway or a new distributor increases scope with no MVP benefit; do not add it now.

## Android components and user experience

### Component ownership

- `NotificationCoordinator`: application-lifetime policy/mode/state, not the Activity/ViewModel's conversation reducer. It reads private paired-host state through a coordinated store and uses a **read-only** API client. UI repository keeps closing its conversation transport on background exactly as today. Share a credential/state gate so UI and worker cannot overwrite drafts/pending commands or use a retired credential generation. A selected conversation never controls which chats are monitored.
- `NotificationMonitorService` (Phase 1): non-exported `specialUse`, explicitly enabled, immediate persistent notification, one notification SSE, network-aware reconnect, bounded cleanup on IO. Persist desired mode but not a promise of liveness. In-app/persistent Stop disables it and closes transport without cancelling upstream work. OS process restart may use sticky recovery only for previously enabled monitoring, with required notification and valid credentials; catch failures and expose stopped state. No unconditional restart after explicit stop/force-stop.
- `NotificationPollWorker` (Phase 2/fallback): unique periodic work per paired host at 15 minutes or longer, network-connected constraint, ordinary bounded `CoroutineWorker` using journal GET/current-state only. No transcript loop, prompt replay or `setForeground` for continuous work. Stop/cancel on forget, denial or master off. A one-time sync after push may be expedited with quota-aware fallback, but no instant promise.
- UnifiedPush callback: use the **current connector's** non-exported `PushService` application callback, library-managed exported IPC components, current registration identity/crypto validation and OS distributor picker. Do not copy an old hand-written exported receiver. Its public Android spec permits an optional distributor foreground-importance bind for about five seconds; this is implementation-specific, not an FCM exemption or unbounded fetch budget. Keep callback small: decrypt/validate/store metadata and post generic notification locally, then schedule sync. Inspect the exact merged library components and token/caller validation. [Connector API](https://unifiedpush.org/kdoc/connector/), [Android spec](https://unifiedpush.org/developers/spec/android/).
- An optional short network sync service is **not necessary for generic push display**. If added, choose valid `dataSync` for finite fetch, handle timeout, prove lawful background-start basis (tested distributor bind/user action/exemption), and fall back to worker if not allowed. A plain UnifiedPush broadcast must never start the long-lived MVP service by assumption. Pure wake-only users should expect degraded delay if fetch is deferred.
- Explicit immutable `PendingIntent` opens `MainActivity` with local paired-host/session IDs; validate IDs/current pairing/read scope before navigation/fetch. Never route arbitrary provider URLs, start an Activity automatically or approve/cancel from notification actions. Unknown/deleted session opens the list with a safe explanation.

### Channels and display

Create stable channels on minSdk 26+: `agent_answers` (DEFAULT), `desktop_attention` (DEFAULT; owner can enable heads-up/high importance in system settings), `notification_monitor` (LOW, silent, ongoing only while monitoring), and `notification_quiet` (LOW, silent digest). Do not create one channel per project/chat or continually recreate channels to override user's sound/importance choices. Maintain one summary plus per-chat state; maximum 50 visible per-chat notifications, then a generic overflow summary. Deduped/cleared/quiet updates never sound again. Attention dominates a completion in the same chat until cleared.

Generic Russian-first strings with English resources: “Ответ агента завершён” / “Проверьте чат”; “Нужен ввод на компьютере” / “Откройте DSH на компьютере: требуется вопрос или разрешение.” Default lock-screen `VISIBILITY_PRIVATE` with a generic public version, no answer snippet/title. Optional authenticated local title display is a separate privacy switch, default off; no title in push. Notification taps may fetch the conversation only after normal authorization. No DND bypass, full-screen intent, notification-policy access or auto-read acknowledgement merely because a signal arrived.

When the exact chat is visible and authoritatively up to date, default suppress its duplicate audible completion; still ingest/dedupe. Other chats notify normally. Reading/dismissing is distinct from turn completion and does not execute anything on host.

### Permissions and battery settings

Before implementation, review the **merged signed-release** manifest, not only the source. Phase 1 explicitly requests `POST_NOTIFICATIONS`, adds general/type-specific FGS normal permissions and optionally `ACCESS_NETWORK_STATE` for supported reconnect behavior. User grants notification access, not background execution immunity.

Prefer opening `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` and explaining why; this does not require `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Direct exemption request adds that normal manifest permission and should be a later explicit owner decision. Do not add battery exemption on first launch. Show checked `isIgnoringBatteryOptimizations` state, but no “reliable” green badge based only on it. `WAKE_LOCK`, if eventually needed, is normal permission for short timeout-bounded work; it does not bypass Doze.

WorkManager's upstream manifest declares `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`; permissions may merge even for ordinary workers. Verify the approved pinned release rather than copying the development-main manifest as a pinned contract. Existing network-state removal must not silently break connectivity constraints. [AndroidX upstream manifest](https://raw.githubusercontent.com/androidx/androidx/androidx-main/work/work-runtime/src/main/AndroidManifest.xml). No `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, overlay/VPN/admin, contacts, storage or location permissions are required by this design.

### Settings UI

Replace the existing unavailable-notifications explanation only when host capability exists; keep a safe unsupported state for older hosts. Add a Notifications section with:

- Master opt-in off by default; switches for answer-finished and desktop attention. Rationale + runtime permission after enabling, not at first chat launch.
- Delivery picker: **Off / Foreground monitoring / UnifiedPush / Delayed polling**. “Recommended” on a configured tested distributor, not on an absent one. Missing/uninstalled distributor offers setup/change or explicit fallback; never secretly starts monitoring. Show provider server origin, no full endpoint; explain external metadata versus self-hosting.
- Status separates **enabled preference**, **running service/distributor**, **host reachable**, **push registration**, **producer coverage**, **permission/channel allowed**, **last sync/test receipt** and **VPN route**. “Enabled” alone is not “working.” Background liveness cannot be continuously inferred by an inactive UI.
- Project row override with “Inherit” and two event toggles; chat menu override with the same inheritance. Only authorized projects/chats listed; new supported projects inherit device defaults. Read-only grants can receive alerts. Clear pending local alerts after confirmed scope loss.
- **Quiet mode**: manual until resume or a selected duration, plus optional local-time interval (minute-of-day, days, overnight wrap). Interpret timezone/DST from phone configuration; no UTC inference. Quiet receipt stores events but posts no audible/pop-up alerts; optionally a silent digest. On exit, one silent digest of still-current attention/recent unseen answers, not a replay burst. Evaluate on next receipt/sync/UI resume; no exact alarm to guarantee the minute of exit. Attention obeys quiet mode by default; bypass requires an explicit separate switch, still no OS DND bypass.
- Generic preview privacy switch (off by default), notification channel settings links, Battery/OEM help, test delivery button and persistent Stop. Explain OnePlus optimization/sleep/autostart/Recent-lock guidance with OS-version caveat. For UnifiedPush configure distributor too; for tailnet routes configure/test Tailscale background availability. Do not claim DSH Mobile can keep the VPN running.
- Russian/English strings, 48-dp touch targets, screen-reader labels, font scaling, no technical secret identifiers, distinct offline/denied/unconfigured/stopped states. Permission denial must not block ordinary chat access.

## Failure modes and recovery contract

| Failure | Required behavior |
| --- | --- |
| PC asleep/off, host plugin down | No new event production; never wake PC or pretend finished. Existing journal resumes when host returns. Current waiting can be reconciled; short missed completed turns may be unrecoverable from bounded upstream cut, report coverage gap. |
| App background/Activity closed | A continues only if enabled service survives; C can deliver via running distributor; B is delayed. Closing UI never cancels desktop work. |
| App force-stopped / distributor force-stopped / phone off | No guarantee or workaround. On next launch restore capability, registration and cursor/current-state; respect explicit off. OS Task Manager Stop is tested separately because scheduled work semantics differ. |
| Doze/OEM sleep or power setting reverted | A/distributor/network sync may stall; show configuration guidance and test results, no exact-alarm/restart abuse. Retry/catch up on permitted execution. |
| LAN exit / Tailscale disabled | Direct fetch fails safely. Public encrypted push may show generic alert; private push cannot reach. No unsafe alternate endpoint, certificate bypass or LAN-only mobile-data claim. |
| Wi-Fi↔mobile / relay/control restart | Close old tunnel resources, new SSE replays metadata cursor, current state repairs attention. Existing inner pin and outer grant semantics unchanged; no prompt bytes replayed. |
| Duplicate/out-of-order feed/push/poll / crash around display | Persistent event dedupe and stable tags; no cursor advancement from sparse push batch; bounded retries may replace an alert without another sound. No exactly-once display claim. |
| Completion cancelled/failed/ambiguous / assistant intermediate end | Do not emit success wording. Unknown upstream shape degrades producer until inspected, no reason/text dump. |
| Waiting resolved before delivery | Inner feed state can drop/silently clear stale attention; encrypted offline alert may temporarily be stale until `attention-cleared`/state sync. Generic copy avoids asserting a currently pending executable action. |
| Grant narrowing/archive/workspace remap/revocation | Revalidate at each boundary, prune outbox/journal visibility, close stream; remove known stale local alerts on sync. Previously delivered metadata cannot be recalled offline. |
| Journal prune/DB restore/producer gap | `resync-required`, new epoch if appropriate, baseline current waiting silently, no fabricated completion recovery. Show reduced coverage. |
| Notification permission/channel disabled | No false test PASS; show blocked, stop notification-only A by product policy. Retain private policy for possible re-enable without credential leaks. |
| Push registration changed / ntfy removed / 404/410 | Mark unconfigured, retire old keys/endpoints, new challenge and user-selected distributor. Offer explicit A/B choice. |
| Provider abuse/outage/rate limit/malicious endpoint | Bounded outbox/backoff, SSRF guards, no sensitive logs; journal unaffected. Provider acceptance is not delivery. |
| TLS certificate/pin/relay grant expired | Stop/review/re-pair as existing contracts require. Never trust-all or reuse bootstrap grant. |
| Quiet mode/timezone change | Reevaluate locally, suppress stale burst, do not bypass OS DND or use exact alarms. |

## Test plan and release gates

This is a **future implementation plan**. No JVM, build, instrumentation, emulator, real-DSH canary or physical-phone notification tests were run for this document. Keep each evidence class separate. Use synthetic sessions, disposable grants and an explicitly authorized isolated PC/server/device; no live DSH or existing owner chats as fixtures.

### Host and relay public-boundary tests

- Global event producer observes two projects, multiple unseen/new PC-created sessions while no phone chat is open; excludes subagents, archives, missing directories and unauthorized/read-narrowed projects. Wildcard current/future scope works within source bounds. Registry replacement/remap during filesystem awaits fails closed.
- Exact `turn/end:completed` after durable final surface creates one answer event; intermediate assistant/step/attempt end, generic status false, cancel/error/unknown end reason do not. Queued next turn and duplicate/old seq never double-notify. Cold `follow` iterator has exactly one `next`, always closes, and never executes a dormant session.
- Questions/approvals from live events and cold projections, already-resolved pairs, continued questions, multiple overlapping reasons, waiting re-entry and startup baseline all have correct episode/clear semantics. No private reason/text/arguments in stored or emitted metadata.
- Crash between source watermark/journal/outbox operations, plugin restart, DB restore/new epoch, seq gap, history cut and startup event buffering. Journal is transactional and replay within retention works; unavailable upstream replay is explicitly degraded rather than counted as recovered.
- Endpoint schema, bearer ownership, grants/revoke during page/write/publish, cursor MAC/device binding, pagination transaction cuts, expiry/prune, settings idempotency/conflict and lost test/registration responses.
- Body/frame/queue/stream/producer limits, rate maps, slow readers, cancellation/shutdown disposers and resource cleanup. Existing prompt receipt semantics remain unchanged; notification sync never invokes mutation adapters.
- Push: RFC examples/key validation, wrong keys/ciphertext, challenge proof/expiry/replay, host-local wrap protection, VAPID, endpoint renewal, encrypted event/wake envelopes and size, outbox merge/retry/TTL/404/410/429. Provider sees no plaintext/private headers. SSRF: private/link-local/IPv6/mixed DNS, DNS rebinding, redirects, path/URL tricks, cross-origin auth leaks and exact operator private allowlist.
- Relay regression: same wire version, pin/trust/name/date before inner credential bytes, revoke/generation/restart and one long notification SSE across bounded TLS chunks. Opaque relay cannot infer logical event semantics; no HTTP-body inspection introduced.

### Android JVM tests

- Strict wire parser bounds, enum/version/IDs/cursor validation, malformed UTF-8, expired push, sparse push not advancing replay cursor, dedupe/epoch resets and local notification identity collision-safe behavior.
- Coordinator ownership against UI lifecycle, concurrent credential/store generation and logout; worker/service/client never dispatches prompts or consumes unsent drafts. Retirement/cancellation remains IO-owned and never holds lifecycle lock across socket close.
- Preferences inheritance and authorization narrowing, per-chat suppression while visible, quiet schedule/DST/overnight changes, delayed digest, no alarm usage, wrong/disappearing session tap.
- Fake clock/provider/server for retries, 429/backoff, network transition, disconnect, bounded pagination, registration challenge/update/remove and process restoration. WorkManager test driver only after its dependency is approved; JVM test success is not OS background acceptance.

### Instrumentation / emulator

Run the existing project build wrapper and test conventions in a later authorized task; do not upgrade AGP/Kotlin/Compose/SDK to add notifications. API coverage: 26, 32, 33, 34, 35, 36, with 33 permission and 34–36 FGS-type cases. Verify signed release merged permissions/dependency tree for no GMS/Firebase/telemetry and backup exclusions; distinguish release from fixture debug transport.

- Enable/rationale/grant/deny/dismiss, denied channels and settings links; Activity rotation/process recreation; font scale/light/dark/TalkBack; tap from locked screen without content disclosure; blocked permission does not prevent chatting.
- Persistent notification start/Stop, expected specialUse declaration, immediate foreground promotion, background-start denial handled without crash/restart loop, reboot/manual resume, Android Task Manager Stop, Recent swipe and Settings Force stop as separate cases.
- Notification journal catch-up after process death and Wi-Fi/mobile transport switch, no duplicate sounds; attention-cleared removes stale notification; no arbitrary URL/action execution. Instrument two non-FCM distributors, including non-Firebase ntfy, endpoint lifecycle and actual callback ownership on API 36.
- Worker constraints/quota/backoff/no-FGS indefinite work on Android 16; test remote wake while client nonexempt, and encrypted generic notification display without a host fetch. Do not assume a distributor's own battery exemption propagates to the client.

Planned Doze commands below are for an **authorized disposable emulator/device only**, and were not executed during research:

```text
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle
# Produce synthetic answer-finished / attention-needed on isolated host.
# Observe distributor/service, receipt/display, latency and network/sync separately.
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell am set-inactive dev.dshmobile.app true
# Compare delayed polling and recovery after active/user launch.
adb shell am set-inactive dev.dshmobile.app false
adb shell cmd activity stop-app dev.dshmobile.app
# Separately test Settings Force stop / am force-stop; relaunch explicitly.
```

Exercise A with/without battery exemption, C with distributor/client separately exempted, and B during deep idle/maintenance/recovery. Expect B not to provide immediate deep-idle delivery. Use foreground-start/timeout compat tests only on disposable devices; `dataSync` six-hour behavior is a rejection check for that alternative, not something to wait through for specialUse. Always restore forced-idle/battery/standby test state. [Official Doze test instructions](https://developer.android.com/training/monitoring-device-state/doze-standby?hl=en).

### Physical OnePlus and operator acceptance

Requires new explicit access; capture model, OxygenOS/Android build, signed APK, distribution flavor/distributor/server versions, exact enabled battery/network settings, charger state and network type in private acceptance evidence. No personal content, real addresses or tokens in tracked results.

1. A/B/C independently: app background, screen off stationary for ≥2 hours, 8–12-hour overnight and 24-hour soak. First default power settings, then only documented owner-enabled exemptions/Recent-lock/sleep optimization changes. Repeat after reboot and an OEM update if available; not one short screen-off success.
2. Synthetic source events in at least two PC-origin chats not selected on phone: answer completion, question, approval, resolved-before-delivery, cancellation/error, concurrent turns and burst/coalescing. Record host event→phone receipt→notification display timestamps separately (clock offsets noted), counts, duplicate sounds and missed/late events.
3. Wi-Fi→mobile→Wi-Fi through public opaque relay, tunnel idle/reconnect/restart; separately direct Tailscale with VPN active, disabled and restored. LAN-only outside LAN is expected failure, not a supported path. Confirm phone distributor/server reachability and host reachability independently.
4. Force-stop DSH Mobile/distributor, Task Manager Stop, OEM clear-all, Battery Saver/Data Saver, notification denial/channel mute, permissions changed after enabling, host sleep and provider outage. Confirm safe degraded copy and catch-up, not unstoppable restart.
5. Compare baseline versus A versus shared C versus B for 24-hour battery use, radio/wake behavior and data traffic under matched signal/activity, using OS battery reports and private diagnostics. No telemetry/upload. Select an owner-accepted battery budget only after measurement.

Provisional acceptance targets, **not product guarantees**: ≥95% of synthetic primary-mode alerts displayed within 15 seconds and all within 60 seconds in tested available-network/non-force-stopped conditions; no wrong-success alerts, unauthorized metadata or repeated audible duplicates; overnight count/latency/battery report retained. Test at least 100 events across states plus burst tests. Deferred B results are reported as actual observed latency distribution with no fixed ceiling. If targets fail, mark mode/device support degraded and investigate before recommending it. Passing AOSP emulator does not establish OnePlus support; a fixture pass does not establish real upstream hooks.

## Phase 1 as built

This section supersedes the proposal above **only for implemented Phase 1**. The remaining UnifiedPush, worker, schedule, test-push and physical-device sections remain future work. No cloud distributor, Firebase, new dependency or toolchain change was added.

### Governing evidence and implementation

The installed DSH rc.2 emits committed assistant messages separately from stream/attempt settlement. A success alert therefore needs an actual `turn/end.reason.kind:completed` plus a still-visible non-interrupted assistant text node with the same turn. Cancellation, intermediate messages and `running:false` are insufficient. One global Cordis `session/event` subscriber retains only sequence/type/time/turn/completed metadata; the producer resolves a single cold opening frame and closes its iterator, never pulls the activation frame. Supported top-level sessions are discovered independently of phone pagination, including new PC-origin IDs. Ambiguous/history-cut/unknown evidence suppresses alerts and degrades coverage; continued question projections are not actionable alerts.

The existing SQLite runtime lock owns one producer. Its journal and cursor key are private records in the same DB; live event fan-out and producer watermark commit together. Startup baselines old completion history (no storm), preserves still-pending episode identities, and subscribes before discovery with a bounded queue. Reads are serialized, ten-second source deadlines, 60-second reconciliation of new/running/attention sessions. This lean version uses concurrency **one**, not four. Producer/session/device bounds and emission limits are explicit. A gap resets the journal, recovering current pending rather than claiming an unlimited upstream replay contract. Restart does not replay downtime completions.

Android uses the existing pinned direct or end-to-end opaque-relay transport in a separate read-only service-owned HostApi; it does not enable repository foreground or touch drafts/command replay. Notification preference/cursor/dedupe metadata is a separate Keystore AES-GCM AtomicFile with a process-wide mutex and no backup. Preferences apply locally first, reconcile with GET/revision, retain a dirty marker offline; master disable cancels local observation immediately and attempts host sync for at most two seconds before service teardown. Forget stops the service, cancels notifications and resets its private state before repository forget. A terminal TLS/pin/auth/schema failure stops retries and local monitoring until explicit re-enable. Network failures use jittered exponential 1–60 second retry respecting SSE Retry-After. SSE callback queue is four bounded pages; catch-up yields after four pages/30 seconds, each fully applied page persists cursor+IDs atomically before display.

### Explicit simplifications

- One boolean per project/chat controls both answer and attention; no per-kind defaults, quiet schedule, visibility preference, visible-chat suppression, test-delivery UI or delayed worker. One paired host remains the product boundary.
- Reset/current attention is inline with the head page (max 1,000), no separate paged state/generation endpoint. Stable epoch/cursor and synchronous head capture avoid a second-fetch cut race. Very large pending-state frames close rather than silently truncate JSON; >1,000 pending recovery is not full coverage.
- All live SSE messages are bounded `notification-page`, not individual notification/resync/coverage variants. No new low-level replay promise. Coverage has no reason enum and is conservative/sticky after a proven gap.
- Journal metadata is bounded JSON rows in the existing SQLite DB rather than normalized event tables. Normal live fan-out/watermark is atomic; periodic recovery controls may be committed separately, with startup authoritative baselining and stable episode dedupe. Retention is lazily pruned at reads/emissions rather than a dedicated daily job.
- Privacy is fixed: private content contains only sanitized project/chat names from authorized ordinary reads, generic fallback otherwise; public lock-screen version is generic. No answer/question/tool contents, URL, tokens or arbitrary actions. Separate answer/default, attention/high, connection/low channels; stable collision-free device+session+kind tags, `onlyAlertOnce`, attention clear cancels the same alert. Crash between persisted receipt and platform notify can lose display; no exactly-once OS claim.
- Explicit opt-in `specialUse` foreground service with permission rationale, POST_NOTIFICATIONS request, persistent “DSH Mobile на связи” and “Отключить”. `START_NOT_STICKY`, no boot receiver, alarm, wake lock or automatic post-force-stop resurrection. Reopen app to resume an enabled setting. Battery settings helper opens the system page, does not request exemption; OnePlus copy is guidance, not certification. Google Play specialUse review remains a distribution gate.

### Verification boundary

Host unit/public HTTP/SSE tests exercise terminal proof, cold safety, coalescing/clear, cursors, current grants, startup/restart, policy conflicts, retention, emission overflow and stream caps. Android JVM tests exercise policy, reset/dedupe, reconnect and real MockWebServer SSE/auth/Retry-After/frame bounds; the existing debug/release/unit/lint/androidTest-compile gates are run. These are synthetic/fixture and compile evidence, **not live upstream or OS background delivery acceptance**. Emulator smoke is deliberately skipped if Pora_API_36 already has an owner process (even if adb lists no devices); no existing emulator or physical phone is reclaimed. Doze, process-death display, locked-screen tap, API26/33/34 behavior and OnePlus overnight battery/delivery remain operator gates.

## Owner decisions needed

| Decision | Recommendation | Effect |
| --- | --- | --- |
| Meaning of “closed” and reliability expectation | Accept Activity closed/background/ordinary process death under tested setup; exclude force-stop/offline/PC sleep from instant guarantee. | An absolute force-stop guarantee is impossible here. This is the first acceptance decision. |
| MVP delivery and persistent notification | Accept opt-in A with honest specialUse and power help, then C; if permanent DSH notification is unacceptable, choose UnifiedPush-first. | Determines MVP effort and whether a distributor/server is mandatory immediately. `remoteMessaging` remains a design-fit review if service scope changes to actual message sync. |
| ntfy ownership/provider | Self-host ntfy on an approved VPS beside, but separate from, the opaque relay; use non-Firebase Android flavor. Private Tailscale ntfy is valid for owners who want no external push provider. | Needs separate service deployment/maintenance approval; no such service is deployed by this document. |
| Push content | RFC 8291 encrypted generic metadata/event envelope, no title/text/arguments; wake-only as an explicit stricter privacy option. | Minimal encrypted envelope avoids requiring immediate host fetch to show a generic alert, at the cost of temporarily stale offline state. |
| Delayed polling | Add optional WorkManager in Phase 2 as recovery, not as advertised urgent notifications. | Accept merged normal permissions and dependency audit; do not keep today's three-permission claim after implementation. |
| Battery configuration | User-led system settings, no direct exemption request/boot auto-start in MVP; test actual OnePlus and distributor/VPN. | Owner accepts power trade-off and provides model/access for later gate. Boot restoration/direct exemption can be considered separately. |
| Defaults/new projects | Master off until enabled; then both event kinds on in all read-granted supported projects, chat/project overrides; quiet mode includes attention. | Prevents unrequested installation alerts; wildcard users explicitly accept future-project inheritance. |
| Completion meaning / error alerts | “Answer finished” only proved completed turn with final visible assistant answer; cancelled/error ends excluded. | Optional separate “Task ended” error notification requires a future event kind/wording decision and upstream outcome inspection. |
| Retention/privacy budget | Seven days / 2,000 metadata entries/device, 24-hour audible freshness, no transcript cache or telemetry; generic lock-screen content. | Needed to approve new private metadata storage and bounded recovery. |
| Secret protection | Windows DPAPI-wrapped publisher secrets where supported, with reviewed explicit wrapping-key option for portable hosts. | Implementation/security decision before storing Web Push auth/VAPID credentials. Never plaintext tracked config or reuse of bearer secrets. |

## Research confidence and source notes

Primary Android pages and current UnifiedPush/ntfy documentation were searched and fetched. Android article extraction initially returned navigation-only/truncated bodies; their official HTML article content was subsequently read without saving files using noninteractive HTTP extraction. Context7 was explicitly attempted for Android Developers but returned **monthly quota exceeded**; no Context7-backed API claim is made. Official docs above are the fallback, not model memory. OnePlus behavior is community guidance plus a mandatory actual-device gate. Six cross-source governing claims were checked once against supplied source excerpts: all supported, none contradicted/unsupported; this advisory evidence check is not an implementation test.

Additional reference entry points:

- [Android 14 FGS requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required?hl=en).
- [WorkManager repeat timing and constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work?hl=en), [PeriodicWorkRequest reference](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).
- [UnifiedPush distributor choices](https://unifiedpush.org/users/distributors/), [implementations and Web Push libraries](https://unifiedpush.org/developers/implementations/).
- [ntfy server access control](https://docs.ntfy.sh/config/#access-control), [encrypted binary UnifiedPush publication](https://docs.ntfy.sh/publish/#unifiedpush).

This research does not certify a foreground type for all future Android versions, promise ntfy interoperability without version-pinned tests, or resolve missing upstream replay semantics by inventing an endpoint. Capability acceptance remains build-, transport- and device-specific.
