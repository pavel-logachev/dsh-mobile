# Mobile API v1 — direct and optional relay transport

The `/v1` API and durable command semantics remain unchanged. Direct invitation v1 is supported alongside remote invitation v2 in the planned v0.2 product. Invitation version, relay wire version and mobile API version are distinct. [Relay protocol](<RELAY_PROTOCOL.md>) is authoritative for v2 transport, grants and publication acknowledgement; public/native acceptance is still pending.

All routes are under `/v1`. JSON uses UTF-8 and camelCase. Timestamps are epoch milliseconds. Opaque IDs are URL-encoded path segments. Unknown response properties must be ignored by clients; required fields may not be inferred. JSON error envelope: `{ "error": { "code": "unauthorized", "message": "Safe user-facing message", "retryable": false } }`. Do not include exception stacks, host paths or credentials. No raw DSH RPC is exposed.

## Transport and pairing

Production: HTTPS. An operator creates a short-lived invitation locally. Debug-only HTTP is allowed only on exact loopback hosts `127.0.0.1` and `localhost` (Android reaches the host through adb reverse). Reject URL userinfo, query, fragment and unexpected base path. HTTPS invitations may specify a single PEM certificate as an explicit trust anchor; hostname and certificate validity checks still apply. For CA-issued certificates ordinary system trust is used. Always require the invitation's SHA-256 SPKI pin for HTTPS.

Direct invitation v1 JSON (QR scan, file import or manual paste):

```json
{"version":1,"baseUrl":"https://computer.example:9443","pairingToken":"<one-use-secret>","pinSha256":"sha256/<base64-SPKI-sha256>","certificatePem":"<optional-local-trust-anchor>"}
```

### QR invitation envelope (`dshm1`)

Android scanning is fully offline (CameraX + ZXing QR-only), with no Google Play services dependency or telemetry. Optional `CAMERA` access is requested on demand after the scan action/rationale, never for file import/manual paste; denial or no camera preserves both alternatives. Frames and payloads stay in memory; the scanner never saves/logs/sends them and the camera stops before trust review. Only the subsequent explicitly confirmed pairing step may contact the invited PC.

QR scan, file import and manual paste accept either the unchanged invitation JSON (v1 or v2), or exactly `dshm1:` followed by canonical **base64url without padding** of a single **zlib-wrapped DEFLATE** stream of that JSON's UTF-8 bytes, through the same bounded decoder and invitation parser. Use Node `zlib.deflateSync(Buffer.from(json, 'utf8')).toString('base64url')`; Android uses `Inflater(/* nowrap = */ false)`. Raw DEFLATE (`deflateRawSync` / `nowrap=true`), gzip, dictionaries, concatenated streams, trailing bytes, padding, whitespace inside the compact envelope and other prefixes/URLs are rejected. Envelope version `1` is independent of invitation/API versions.

Limits: raw JSON and inflated UTF-8 JSON ≤65,536 bytes; compressed bytes ≤65,536; complete compact text ≤87,388 ASCII characters (6-character prefix + at most 87,382 base64url characters). Validate the limits before allocating/expanding, require a complete stream with a valid checksum and strict UTF-8, then reject JSON nesting deeper than 32 containers with a linear scan of braces/brackets outside strings (respecting escapes) **before** recursive parsing. Apply the **same** invitation validation and host/endpoint/SPKI-fingerprint trust confirmation to all three inputs; confirmation is bound to the exact immutable parsed invitation shown, not the current editable text. Scanning never pairs automatically. New input cancels/versions pending file reads and ignores late completions. Only the scanner's non-secret route boolean survives rotation; payloads and trust review are never saved, and a decode delivered after lifecycle stop/disposal is discarded for safe re-scan. Never log or persist the QR payload/decoded invitation; it contains one-use secrets. A host must fall back to a file when the invitation cannot fit a QR symbol; do not truncate it.

Import invitations only from the owner's trusted desktop/local channel. A pin cannot authenticate an attacker-substituted whole invitation that replaces both endpoint and trust material. If pairing succeeds on the host but the response is lost, do not replay it expecting credential recovery: inspect/revoke the potentially issued device grant locally and create a new offer.

Invitation secrets are not loggable. Neither are URLs with the invitation encoded in a query. `POST /pairings` body `{pairingToken, deviceName}` → 201 `{deviceId, deviceToken, hostName, protocolVersion:1}`. A pairing offer defines separate readWorkspaceIds/executeWorkspaceIds. Execute scope is a subset of read scope. Expired/reused/unknown offers receive the same 401 error. Rate-limit failures. Host stores only token hashes; Android protects its stored credential with Keystore. Pairing itself is transactional.

Remote invitation v2 adds an invitation deadline and independent outer relay route/access capabilities, while keeping inner `POST /v1/pairings` and device bearer authentication. Inner authority is exactly `https://h-<32hex>.dsh.invalid`; its trusted certificate, validity/hostname and SPKI pin checks remain mandatory through fixed authenticated CONNECT. Outer WSS trust is separate. Successful v2 pairing additionally returns `relayAccess: {accessId,accessToken,expiresAt}` only after current-generation grant publication is acknowledged; failure revokes the pending new device/grant. The phone atomically encrypts permanent access with the bearer and retires bootstrap; no recovered bootstrap fallback. Invitation lifetime ≤15 minutes, device access initially 365 days (then explicit re-pair). See [the relay contract](<RELAY_PROTOCOL.md>) for exact fields and recovery. Relay restart/reconnect never retries a mutation or changes its original receipt.

All other routes require `Authorization: Bearer <deviceToken>`. Device revoke is a local admin operation, not a broad public admin endpoint. `DELETE /device` revokes the current device and returns 204; local sign-out may erase the phone credential even if unreachable, clearly distinguishing it from host revocation.

## Reads

- `GET /capabilities` → `{protocolVersion:1, hostVersion, hostName, upstreamVersion, capabilities:{sessions:true, textPrompt:true, cancel:true, liveSnapshots:true, steer, queue, rename, models, questions, questionSkip, attachments, fileDownload, approvals:false, push:false}, storage:{pendingCleanups,cleanupFailures}}`. Host plugin 0.9.0 publishes `hostVersion:"0.9.0"`, the separate phone feature flags introduced in 0.7.0 and described in [Phone features](#phone-features-additive-v1-extension-host-plugin-070), and the notification fields described in [Background notifications](#background-notifications--phase-1-as-built); `approvals` stays `false`. `storage` is path-free operational evidence: the number of staged entries awaiting cleanup and the number of failed unlink/retry operations since process start.
- `GET /workspaces` → `{items:[{id,name,canExecute}]}`. IDs map to owner-selected canonical roots: configured opaque IDs in legacy explicit-list mode, or DSH workspace UUIDs in live registry mode. Registry order matches the sidebar, names are sanitized, missing directories are omitted and the first 100 registrations bound the scope. No raw paths in client responses. Permission policy is host-local; wildcard `['*']` grants cover current/future source workspaces without any new mobile request field.
- `GET /presets` → `{items:[{id,name}]}`. Empty list means host default only.
- `GET /sessions?workspaceId=<optional>&limit=50&cursor=<optional>` → `{items:[Session],nextCursor:null|string}`. Maximum limit 100; nextCursor is an opaque validated keyset position. A session whose updatedAt moves above the cut is not repeated on later pages; refresh the first page for a fresh index. Filter unauthorized sessions before pagination. Exclude subagent sessions and, in registry mode, archived sessions before pagination. Exclude `blank:true` sessions except IDs created by mobile (including a returned adopted ID), retained in host state for seven days, at most 256 IDs, until observed nonblank. Missing `blank` fails open. This index filter does not restrict GET/SSE by ID. Each request resolves distinct cwd bindings anew; no cross-request cached workspace identity may authorize metadata.
- `GET /sessions/{id}` → `Snapshot` (bounded latest history, max 100 messages). Cold read, do not resume execution merely to read.
- `GET /sessions/{id}/events` → SSE stream. Send `event: snapshot` with a complete `Snapshot` on open and after material changes; heartbeat comments at most 20 s apart. The first snapshot on every connection replaces client history, including after plugin restart. Bounded active streams/device. No bearer in URL, no Last-Event-ID replay guarantee. Close revoked/unauthorized clients or streams whose registry entry/read grant is removed. Grant rechecks also republish changed `canExecute`, even on idle conversations; grant replacement is local administration, not a new remote endpoint. No lossless low-level event claim.
- `GET /commands/{requestId}` → `CommandReceipt`; only the issuing device can read its own receipt; 404 means no persisted receipt known, NOT proof that an arbitrary upstream task never ran.

```ts
type Session = {
  id: string; title: string; workspaceId: string; updatedAt: number;
  running: boolean; canExecute: boolean;
  blank?: boolean; // DSH empty-session metadata; absent means unknown
  waitingForAnswer?: boolean; // open or continued agent question; absent means false
  runningSubagents?: number; // integer >= 0; running delegated descendants
};
type Message = {
  id: string; role: 'user'|'assistant'|'system'; text: string;
  createdAt: number; requestId?: string; provisional?: boolean;
  kind?: 'message'|'agent_event'|'context'; // absent means ordinary message
  serviceText?: string; // exact removed suffix including separators; text + serviceText reconstructs original
};
type Snapshot = {
  session: Session;
  messages: Message[];
  cursor: number; // safe integer >= -1; -1 is the exact DSH empty-journal sentinel
  hasMore: boolean;
  activity: 'idle'|'running'|'waiting'|'unknown';
  runningSubagents?: number; // integer >= 0; same as session.runningSubagents
  notice?: string;
  activityDetail?: { turnStartedAt: number; tool?: string };
};
```

Session titles may be empty; clients apply their localized untitled fallback. `waitingForAnswer` is independent of `running` and is included in list items and snapshot sessions; clients update it through the same index refresh and replacement-snapshot path as `running`.

History must respect DSH surface replacements; do not expose discarded intermediate assistant attempts as final answers. Replacement endpoints refer to current surface order, not numeric sequence ranges. If a long, compacted chat's bounded history window cannot resolve an older replacement record within the recovery bounds (16 pages / 16 MiB, see [the architecture](<ARCHITECTURE.md#delivery-and-recovery>)), host plugin 0.7.0 keeps and returns the certain recent tail of messages with `hasMore:true` and the fixed history-cut notice, instead of an empty message list; it does not retain potentially discarded answers. Earlier history of such chats may therefore still require the desktop until full history reconstruction/paging is implemented. Unsupported events, sequence gaps and oversized openings still fail closed. Hidden/tool/unknown event types are not dumped as raw JSON. Agent action approvals, and questions on a host without the `questions` flag, must show a neutral notice to return to the desktop, not silently present an idle task.

### Running subagents (additive v1 extension, host plugin 0.6.0)

`runningSubagents` is an optional integer >= 0 on each `Session` in `GET /sessions`, on `snapshot.runningSubagents` and on `snapshot.session.runningSubagents` in GET/SSE snapshots. Values are the same for the same session state. It counts running delegated descendant sessions created by subagent tools with origin `subagent`, including nested descendants; an ordinary independent fork is not counted. Use 0 or omit the field when none are running. A host composition without live services may omit it. Clients must treat an absent or invalid value as 0.

Main-turn status, `Session.running`, `Snapshot.activity` and cancellation semantics are unchanged. Subagent sessions remain hidden from the list. App 0.7.0 can show the count even after the main turn ends; Stop still targets only the main turn. App 0.6.1 ignores the field. This is an additive API v1 extension, not a protocol version change.

### Quiet transcript projection (additive v1 extension)

The host classifies durable `user/message.data.source.kind`, not the user role alone. `agent-message`, `subagent-settled` and `tool-jobs` become `agent_event`; `compact-checkpoint`, `runtime-context`, `time-context`, `agent-instructions`, `skill-catalog` and `plugin:hindsight` become `context`. Known human `source.kind:user` wins over whole-message textual prefix heuristics. Ordinary user messages explicitly carry `kind:message`, so a newer client never second-guesses authoritative host classification. Assistant text and provisional responses are unchanged. Unknown sources stay ordinary unless a strict fallback below matches. Source metadata and tool arguments/results are never added to the wire. Existing context text is retained for on-demand reading, not treated as a security redaction boundary.

With legacy/missing source attribution, the exact case-sensitive fallback rules are:

- Start-of-text `Agent <id> sent a message: `; or `Background subagent <id> ` followed by an inspected completed/stopped/max-tokens/refusal/error sentence immediately followed by `Its closing message:` or `It left no closing message.` → `agent_event`. IDs contain only ASCII letters/digits and `._:-`.
- A whole single-line `background job <id> (<detail>) finished <status>. Read its output with job_output.` → `agent_event`. This uses linear delimiter parsing, not overlapping detail/status regex quantifiers. Relay/settled ID regexes have one disjoint character class followed by literal separators; the time regex is anchored and only runs on a capped 4096-character candidate. [Shared adversarial vectors](<../fixtures/classifier-adversarial.json>) cover every classifier family at 2 MiB.
- The complete installed checkpoint preamble followed by a blank line; the exact `Current runtime context. This snapshot supersedes earlier runtime-context snapshots.` heading plus a blank line; or the exact cleared-runtime-context sentence → `context`.
- Complete suffix blocks `<system-reminder>…</system-reminder>`, `<hindsight_knowledge>…</hindsight_knowledge>`, `<hindsight_knowledge_refresh>…</hindsight_knowledge_refresh>` only at text start or after a blank line. Remove them one suffix at a time, never an inline/prose/unfinished tag or content after a closing tag.
- A suffix beginning `Time sampled while preparing turn <digits>, step <digits>: <ISO timestamp>[<zone>]`, alone or with the exact two runtime line headings (`Browser time zone for this request: …` and `Elapsed since the preceding model-visible message|step context: …`). This too must start the text or follow a blank line. Legacy time candidates are limited to three lines and 4096 characters; longer/unrecognized readings stay visible and unchanged.

A known human `source.kind:user` is never stripped or reclassified: XML examples and exact service envelopes remain intact, with explicit `kind:message`. Only legacy/missing or non-user sources use suffix extraction. A monotonic linear scan identifies disjoint complete ranges and walks their suffix chain once, preserving every character (no trim/newline normalization). The removed suffix including its separating blank line is sent as optional `serviceText`, so `text + serviceText` reconstructs the original exactly. Entirely service-only text is retained in `text` as `context`. All fields, including `serviceText`, count toward the existing 2 MiB serialized ceiling; omit older whole messages or reject an oversized newest message, never truncate either part. The GET/SSE boundary validates and explicitly projects `kind`, `serviceText` and `activityDetail`. Human prose merely mentioning the tag name is untouched. The patterns and redacted shared test vectors are in [the fixture catalogue](<../fixtures/message-noise.json>); source evidence and limits are in [the architecture](<ARCHITECTURE.md#quiet-chat-evidence-and-ownership>).

Android defaults to ordinary user/assistant messages and groups adjacent service messages into a collapsed activity disclosure without crossing an ordinary message boundary. Expansion lists compact tappable previews for agent events, context messages and extracted `serviceText`; tapping any item opens its full selectable/copyable text. Nothing hidden by presentation is irretrievably discarded. Classification runs off the composition thread. Results are tagged with their exact immutable message-list reference. While that reference does not match the current snapshot, show a loading indicator (not old copyable history or an empty-chat label); an authoritative empty surface hides prior messages immediately. Previous groups are retained only as identity bookkeeping, never as a render fallback. Group IDs use the preceding ordinary-message ID, or `history-start` for leading groups; overlapping updates retain an earlier group identity by surviving member IDs when the preceding message falls outside the window. With a host that omits `kind`, Android applies the same text rules in presentation only, never changing canonical receipt reconciliation. Unknown future `kind` values remain visible ordinary content. Legacy fallback can mistake deliberately pasted exact service envelopes for service text; explicit new-host human attribution avoids whole-message prefix false positives.

`activityDetail` is ephemeral and included only for running/waiting, unambiguous snapshots with a proven `turn/start` in the bounded journal. `turnStartedAt` is that event's epoch milliseconds. `tool` is the latest current-turn `tool/call` without a matching `tool/result.message.toolCallId`; only a safe tool name (1–128 ASCII letters/digits or `_.:/-`) is sent. Completion, new turn and stopped status clear it; bounded cuts, gaps and unknown surfaces omit it. No extra upstream query or execution is performed. Multiple concurrent tools mean the latest unmatched tool, not an exclusive claim. All existing 100-message/2 MiB bounds remain unchanged. Older clients ignore both new fields; new APK alone hides recognized legacy noise but cannot recover authoritative turn time/tool names or reduce an old host's payload.

## Background notifications — Phase 1 as built

Additive API v1; existing command receipts, TLS pins, relay wire and `push:false` are unchanged. No UnifiedPush/FCM/WorkManager/boot receiver. Capabilities add `notifications:boolean` (feed implementation available; opt-in starts baseline asynchronously) and `notificationCapabilities:{version:1,feed:boolean,unifiedPush:false,coverage:"initializing"|"ready"|"degraded",retentionMs:604800000}`. Coverage is conservative: after a gap or overflow it reports `degraded`, and returns to `ready` only after confirmed full recovery of all affected sources — no pending retry, overflow, unprocessed events or new errors in that pass; this is not lossless replay.

- `GET /notification-settings` returns `{revision,enabled,projects:[{workspaceId,enabled}],chats:[{sessionId,enabled}]}`. Default master off. Both alert kinds share one boolean: chat override > project override > true, gated by master and current read grants. GET prunes unauthorized/stale overrides and increases revision when changed.
- `PUT /notification-settings` accepts the same resource with `expectedRevision` instead of `revision`. Full replacement, changed policy increases revision; identical retry at current/previous revision is idempotent; stale different policy is 409. Strict keys/booleans/unique IDs, ≤100 projects/256 chats/32 KiB. Unauthorized IDs are hidden 404. Preferences do not grant execution.
- `GET /notification-events?after=<optional>&limit=50` returns `{version:1,epoch,items,pending,nextCursor,hasMore,resetRequired,coverage}`. Limit 1–100, scan ≤400. `items` contain only `{version:1,eventId,sequence,occurredAt,expiresAt,workspaceId,sessionId,kind,sourceSeq,turn?,attentionId?}`; kinds are `answer-finished`, `attention-needed`, `attention-cleared`. UUID event/episode IDs, safe sequence, metadata only (no titles, transcript, arguments or approval reason). Current source mapping/archives/read grants filter every page. Pending reset records are `{sessionId,workspaceId,attentionId}`, max 1,000, silent recovery only.
- `GET /notification-events/stream?after=<optional>` sends only `event:notification-page` with that same page and `id:nextCursor`, not the proposal's three separate live event types. It drains bounded pages via a one-second recheck and sends heartbeat comments every 15 seconds. `Last-Event-ID`, if supplied, must equal `after`. One notification stream/device within 3/device and 32 total; ≤128 KiB frame/output budget, close slow writers and replay via cursor.

Missing/pruned/wrong-epoch cursor resets at the current journal head with no completion backlog and a consistent inline pending cut. Device MAC failure/malformed cursor is 400; cursor is canonical base64url of `[epoch,sequence,mac]` (≤256), HMAC-SHA256 binds the authenticated device, epoch and sequence; it is not a credential. Journal/key/producer watermarks persist in the existing SQLite state. Up to 2,000 events/device for seven days; alert display expires after 24 hours. Gaps/producer rate overflow reset epoch, recover current pending silently, never invent missed success. Master/scope changes also reset the pending view. GET retention pruning is lazy; dormant records are pruned at next delivery/read, not by a dedicated vacuum worker.

Additional limits: GET 60/min/device, stream opens 6/min/device, writes 6/min/device within existing 120 total; 429 `Retry-After:60`. Source entries 30/min/session, 120/min/device, 1,000/min/host; 64 enabled devices; 4,096 source envelopes/10,000 active sessions. Queue/capacity loss degrades coverage. Every route requires the existing bearer; no tokens in URL/SSE IDs. The phone service owns a separate pinned read-only transport, posts before atomically persisting dedupe+cursor; a replay silently replaces the same notification identity and never replays an agent mutation.

## Phone prompt outcomes (additive v1 extension, host plugin 0.9.0)

`GET /capabilities` adds **`capabilities.promptOutcomes: true`** when the durable journal reader is available (the existing feature-flag container is `capabilities`, not `features`). Absent/false means clients must not promise phone-task outcome alerts. This capability has no dependency on a notification feed, notification settings/coverage or SSE. `approvals:false` still means approvals cannot be answered from the phone; a pending approval can nevertheless be observed as `waiting`.

`GET /sessions/{sessionId}/prompt-outcomes?requestId=<uuid>[&requestId=<uuid>...][&since=<token>][&waitSeconds=<0..25>]` uses the same bearer authentication, current session/workspace read grants, archived-session handling and errors as session snapshot GET. It never dispatches or resumes a task. Supply 1–32 **distinct** UUIDs; case is ignored, duplicates collapse and results are canonical lowercase in first-request order. Zero IDs, more than 32 distinct IDs or a non-UUID return `400 invalid_request`.

```json
{
  "token": "<opaque string>",
  "outcomes": [
    {"requestId":"00000000-0000-4000-8000-000000000001","status":"completed","turnId":"1","updatedAt":"2026-11-01T12:00:00.000Z"}
  ]
}
```

One outcome is returned for each distinct requested ID. Only this device's persisted **prompt** receipt for this session authorizes journal correlation; unknown IDs, desktop prompts, another device's receipt, other command types and receipts for other sessions return `unknown`, without leaking their existence. Request IDs passed upstream are device-derived as described below. A receipt by itself proves admission, not execution or completion.

| Status | Authoritative evidence |
|---|---|
| `unknown` | No matching journal evidence, or no contract-defined outcome can be proven. Unknown IDs use the fixed UTC epoch `updatedAt`; wall-clock/idle state never invent a transition. |
| `queued` | The request is durably present in the pending inbox, not yet consumed by a turn. |
| `running` | The turn claimed this inbox message or admitted its `user/message` with matching `source.rpcId`, and has not ended. |
| `waiting` | That open turn has an unanswered `ask_user_question` call or an `approval/asked` without its matching decision. Resolution returns it to running; a later terminal outcome still applies. |
| `completed` | Its consuming turn ended with `reason.kind:"completed"`. |
| `failed` | Its consuming turn ended with `reason.kind:"error"`. Only this status includes optional plain-text `summary`, whitespace/control-sanitized, Unicode-safe and ≤2048 UTF-8 bytes. |
| `rejected` | This device/session prompt receipt is a proven host rejection. |

`turnId` is optional until a consuming turn is established and is scoped to the session; queued/steered prompts may share it. Canceled, blocked, interrupted, forked, max-token and future unrecognized endings are **not** success or host rejection and resolve to `unknown`. Unlike other v1 timestamps, outcome `updatedAt` is an **ISO-8601 UTC string** taken from the event that changed the outcome (or the rejection receipt). It does not change for unrelated journal events. Tokens are opaque content-change identifiers, not journal cursors or credentials; every returned outcome change changes the token.

Long-poll semantics: `waitSeconds` defaults to 0 and is an integer 0–25. Only a positive wait with `since` equal to the current token is held. Omitted/different `since` returns immediately. A held request returns 200 on an outcome change or expiry, possibly with an unchanged token. A new held poll for a device completes the previous one immediately with its current authorized state, even across session groups. These polls do not consume the per-device SSE stream quota. Disconnect disposes the subscription; grant/session changes are rechecked before reads/responses. Shutdown promptly completes outcome HTTP requests with the existing `503 unavailable` envelope. Clients must not open more than one held poll per device and should group ≤32 watches/session, round-robin sessions, and perform immediate non-waiting reconciliation after reconnect/restore/foreground.

Derivation reads the raw durable journal, not the bounded transcript/surface. SQLite retains a metadata-only checkpoint per queried prompt receipt (≤32 KiB each, same durable lifetime as the unpruned receipt ledger), including scan position, pending inbox position, consuming turn and unresolved attention IDs. No prompt text/tool arguments are persisted in checkpoints. Each fold reads ≤4096 events in pages of ≤256 and checks a 16 MiB serialized-event budget; ≤128 attention IDs per tracked fold. Checkpoints resume at the saved journal position across host restart and transcript eviction; completed/failed checkpoints need no rescan. A scan that has not reached the journal head exposes `unknown` until a later read catches up. At most four journal folds run concurrently per adapter (≤512 abortable capacity waiters). At most 128 held polls exist host-wide, separately from SSE; existing device rate limits still apply. Event wakeups have a one-second journal/receipt/grant recheck fallback, not a time-based status heuristic.

**Upstream rc.5 limitation:** public `SessionPersistence.open(id,"read")` / `SessionHandle.read(offset,length)` is cold-safe and returns bounded slices, but JSONL internally materializes the entire stored log before slicing. Thus the bounds above cover this plugin's fold/retained state, not the upstream decoder's allocation or physical I/O; no streaming cold-read claim is made. Persistence batching can delay event visibility (the reader never forces a write/flush). This API provides reconciliation, not exactly-once notification/execution guarantees.

## Mutations and durable receipts

The original wire bodies are unchanged; host plugin 0.7.0 added only optional message fields and new routes (see [Phone features](#phone-features-additive-v1-extension-host-plugin-070)), and host plugin 0.7.1 additionally records sessions accepted through `POST /sessions` for the session index (see `GET /sessions` in [Reads](#reads)). Internally the server binds each action to its authorized workspace ID; the adapter rechecks fresh scope and rejects a different workspace mapping before calling DSH. Registry-archived sessions are not valid prompt or cancellation targets. Grant revocation after adapter entry still does not retroactively cancel already-dispatched work.

- `POST /sessions` body `{requestId, workspaceId, presetId?:string}` → CommandReceipt; accepted receipt result `{sessionId}`.
- `POST /sessions/{id}/messages` body `{requestId, text, delivery?:"queue"|"steer", uploadIds?:string[]}` → CommandReceipt. Without `delivery` the message is sent in DSH queue mode; steer happens only when the client explicitly sends `delivery:"steer"` (requires the `steer` flag), never automatically. Reject oversized text; empty text is allowed only when the message has attachments (`uploadIds`, requires the `attachments` flag). Since host plugin 0.7.0 the host passes DSH a per-device id derived from the requestId for correlation, not the mobile requestId itself (see [Request IDs](#request-ids)).
- `POST /sessions/{id}/cancellations` body `{requestId, expectedCursor}` → CommandReceipt. expectedCursor is the safe integer >= -1 from the client's last authoritative snapshot; server and adapter recheck it and running state before admission, rejecting stale state with 409. Cancellation requests a stop of the session's active turn at host admission, not an immutable historical run. DSH rc.2 exposes no atomic target-run conditional cancel, so the conservative cursor guard reduces stale requests but is not a run-identity guarantee. Never replay cancel automatically; refresh and require a new explicit action after conflict. Accepted means requested, not proof the task has already stopped.

```ts
type CommandReceipt = {
  requestId: string;
  status: 'pending'|'accepted'|'rejected'|'uncertain';
  result?: {sessionId?: string};
  error?: {code:string; message:string};
  updatedAt: number;
};
```

Use UUID requestId. `pending` → HTTP202; accepted → HTTP200 (create may use 201); `uncertain` → HTTP202; a known rejection → appropriate 4xx with a persisted rejected receipt returned as `CommandReceipt`. Syntax/auth failures before command admission use the error envelope. Same device/requestId + same canonical action/payload returns original receipt without redispatch; same ID with different payload returns 409 error envelope. Persist dispatch intent before invoking upstream. Adapter resolution means DSH controller admission, never completion of the model/task; a later execution failure cannot retroactively make an accepted command rejected. Only a known pre-admission validation/domain rejection yields rejected. A generic error/abort after dispatch is uncertain because upstream side effects may already have occurred. A server-start-owned recovery converts abandoned dispatches to uncertain; merely opening the local admin database must not change active receipts. A host-owned ten-minute dispatch deadline aborts a call that has not settled and leaves its receipt uncertain; the abort is not proof that no upstream side effect occurred. Never auto-resubmit uncertain upstream work.

Mobile sends once per explicit user action, stores the ID/draft before sending, and queries the receipt on network failure/reconnect. It must not create a fresh ID merely because a response was lost. Show `uncertain` explicitly; keep its draft/receipt available across process recreation. Surface canonical user messages carrying requestId to reconcile accepted optimistic messages; with host plugin 0.7.0 a phone sees its own requestId only for its last 1024 prompt commands (see [Request IDs](#request-ids)). If a receipt stays uncertain or 404, offer an explicit local abandonment after the owner checks the host: warn that work may already have run, clear the local pending record and associated send draft, and never resend or cancel as a side effect. Keep this distinct from a known rejection. No promise of exactly-once execution across unverified DSH persistence.

### Command-ledger recovery hold (introduced in host plugin 0.9.0)

The host keeps a small sidecar witness next to the SQLite file (`<statePath>.ledger.json`) with the ledger identity, epoch and admitted/completed counters. If the witness proves that the database is older than the ledger it observed (restored backup, replaced database or corrupt witness), the host enters a recovery hold instead of accepting commands whose receipts may have been lost.

During a hold, fresh mutations return HTTP 503 with error code `recovery_hold`; `GET /commands/{requestId}`, session reads, SSE and an exact replay of an already-persisted requestId continue to work. A `recovery_hold` response means the host rejected the command before admission: no receipt and no dispatch exist, so a client records it as not sent, never as uncertain, and does not retry automatically. The operator inspects `recovery-status`, reconciles uncertain or missing receipts against DSH history, then runs `recovery-release --new-epoch`. The epoch increment is bookkeeping for that decision; it is not proof that earlier commands never executed. The hold and epoch are host-local and do not change any receipt's status.

The witness detects a database restored without its sidecar. If both files are restored together, or the sidecar is lost, the host cannot prove a rollback and relies on the documented operator procedure instead. This is not a distributed-ledger or exactly-once claim.

## Phone features (additive v1 extension, host plugin 0.7.0)

Host plugin 0.7.0 introduced these features and keeps protocol `/v1`; host plugin 0.9.0 keeps them. `GET /capabilities` adds `hostVersion` (`"0.9.0"` for the current host plugin; earlier versions identify themselves separately) and separate boolean feature flags in `capabilities`: `steer`, `queue`, `rename`, `models`, `questions`, `questionSkip`, `attachments`, `fileDownload`. Notification fields are described in [Background notifications](#background-notifications--phase-1-as-built). Older apps ignore them. Newer apps hide each feature whose flag is absent (or `false`); clients must not render fake controls for it.

Every route requires the device bearer token. Mutations also require the execute grant for the session's workspace. JSON commands use the durable receipts described above: an exact replay of the same requestId with the same canonical payload returns the original receipt, also when replays arrive concurrently; a different payload returns 409, and uncertain delivery is never retried automatically.

| Route | Payload / result | Flag |
|---|---|---|
| `POST /sessions/{id}/messages` | `{requestId, text, delivery?:"queue"\|"steer", uploadIds?:string[]}` → CommandReceipt; empty `text` is allowed when there are attachments | `steer` for `delivery:"steer"`; `attachments` for `uploadIds` |
| `POST /sessions/{id}/queue` | `{requestId, itemId, action:"steer"\|"remove"}` → CommandReceipt; acts on a pending queued message | `queue` |
| `POST /sessions/{id}/title` | `{requestId, title}` → CommandReceipt; `title` is non-empty, ≤256 bytes UTF-8, no control characters | `rename` |
| `GET /models` | `{items:[{provider, id, name, supportsImages:boolean\|null, reasoningEfforts?:[{id, name}]}], default?}` | `models` |
| `POST /sessions/{id}/model` | `{requestId, provider, model, reasoningEffort?}` → CommandReceipt | `models` |
| `POST /sessions/{id}/answers` | Either `{requestId, callId, answer:{answers:[{id, selected:string[], custom?}]}}` or `{requestId, callId, skip:true}`, never both → CommandReceipt; an expired question returns 409 `question_expired` | `questions`; `questionSkip` for `skip:true` |
| `POST /sessions/{id}/uploads` | Raw body with exact `Content-Length`, the file's MIME type and a URI-encoded `X-File-Name` header → 201 `{uploadId, expiresAt, attachment}` | `attachments` |
| `DELETE /sessions/{id}/uploads/{uploadId}` | → 204; removes a staged upload. Only the device that owns the upload, with the execute grant; an upload already taken by an admitted send cannot be deleted | `attachments` |
| `GET /sessions/{id}/attachments/{attachmentId}` | Authorized image download with `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`; the read grant is enough | `attachments` |
| `GET /sessions/{id}/files/{attachmentId}` | File download as `application/octet-stream` with `Content-Disposition: attachment` and a safe UTF-8 filename, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; ≤20 MiB; the read grant is enough | `fileDownload` |

Snapshots (GET and SSE) additionally carry:

- `pendingQueue` — pending queued messages; their IDs are the `itemId` targets of the queue route;
- `pendingQuestions` — open or continued (still answerable) agent questions; their `callId` is the answer target. Each option in `pendingQuestions[].questions[].options[]` carries `recommended:boolean`;
- `resolvedQuestions: [{callId, outcome:"answered"|"skipped", answers:[{id, selected, custom?}]}]` — the last 20 settled question calls;
- `model: {provider, id, reasoningEffort?} | null` — also on `session`; on `GET /sessions` list items only when already available;
- `messages[].attachments`;
- `messages[].delivery?: "queue"|"steer"`.

`messages[].delivery` is correlated with the inbox splice target recorded in the DSH journal: a next-turn target means `queue`, a next-step target means `steer`. The field is absent when this cannot be proven within the bounded history; absence does not mean `queue`.

### Request IDs

For message prompts (`POST /sessions/{id}/messages`) the host passes DSH a per-device id derived from the mobile requestId, so two devices that pick the same requestId cannot collide upstream. Other commands do not use a derived id:

- session create keeps the original requestId;
- cancel does not get a derived id;
- rename, model, queue and answer commands pass no requestId to DSH at all; it is used only in the host's receipt ledger.

In snapshots (GET and SSE) a phone sees its own mobile requestId restored in `messages[].requestId` and `pendingQueue[].requestId` for its last 1024 prompt commands. IDs on other devices' messages are opaque. Prompts admitted by host plugin 0.6.0 keep their original IDs.

### Queue and steer

`delivery:"queue"` uses DSH's own prompt mode `queue`: the message is delivered on the next turn, after the agent finishes the current one. This remains the default. `delivery:"steer"` uses DSH's prompt mode `steer`: the agent sees the message at its next step inside the current turn, without stopping. The queue route either steers a pending queued message now or removes it from the queue on the computer.

### Chat title and model

A rename changes the chat title on the host. A model change applies when the next model request is built, which may be the next step of the running turn; the request already running is not switched. Snapshot `model` is the model for that next model request; `null` means the chat has no durable model selection or request history, and the host does not substitute a default. `supportsImages` is `true` or `false` when known and `null` otherwise; images can be sent only if the chat's model accepts them. `reasoningEfforts` is optional and lists the model's reasoning efforts; `default` is optional.

### Agent questions

Questions come from DSH's `ask_user_question` and are answered through DSH's user-questions service. A question offers options (single or multiple choice) and may allow a free-text answer (`custom`). Each answer entry carries the question `id` and the `selected` option list. Option labels are sent back verbatim. To skip a single question inside a batch, send an empty `selected` without `custom` for it. With the `questionSkip` flag, `skip:true` skips the whole batch; upstream then receives an empty `selected` for each question. A request carries either `answer` or `skip:true`, never both.

`recommended` follows the trailing "Recommended" (case-insensitive) or "推荐" convention in ASCII/fullwidth parentheses or square brackets, with optional whitespace inside and around the suffix. Options additionally expose `displayLabel` without that suffix. `label` remains unchanged and is the answer token. Older clients may ignore `displayLabel` and apply the same display-only parsing.

Both `open` and `continued` questions are answerable. A question may also be answered on the computer or become unavailable; answering an expired question returns 409 `question_expired`. `resolvedQuestions` reports only `answered` and `skipped` outcomes because DSH keeps no durable "expired" outcome. Legacy blocking questions without a call ID are not answerable from the phone. Devices without the execute grant cannot answer.

Agent action approvals are not available to plugins and stay desktop-only; `approvals` remains `false`.

### Uploads and attachments

The app uploads files to the computer right after the user picks them; the message then references them by `uploadId`. When the user removes an attachment chip, the app deletes the staged upload with `DELETE /sessions/{id}/uploads/{uploadId}`.

- Accepted types: PNG, JPEG, WebP, GIF, PDF, plain text and `application/octet-stream`.
- An upload is bound to its device, session and workspace.
- Images are validated by DSH's attachment service at upload time and held in host memory until the send completes. They count against the image staging budget for that whole time: while staged, while uploading and while claimed by a send.
- Non-image files are written to the host plugin's private staging directory: outside any workspace, with random file names and owner-only access. They are uploaded to DSH's attachment store only when the message is sent, right before the prompt. The concurrent-upload limits (2 per device, 4 per host) also apply to this send-time upload; the send waits for a free slot instead of failing.
- Abandoned or expired uploads never reach DSH. Staged files are deleted after the send (whether it succeeds or fails), on expiry, on plugin shutdown and by a sweep at startup. When a device is revoked from any host process, including the CLI, its staged uploads are removed within 30 seconds or at the device's next request.
- Sent files go to DSH's own content-addressed attachment store, not into the project workspace.

Send-time admission and unconfirmed receipts:

- Every attachment uploaded to DSH at send time holds a receipt reservation, capped at 20 per session and 64 per host.
- The caps are keyed to the upstream file-upload service and survive plugin module reloads (including Cordis tracing wrappers). A replacement upstream service starts a new budget; a DSH restart also clears upstream unbound receipts.
- Slots reserved for a message but never used by an upload (for example, the send failed before uploading) are released immediately.
- A failed upload releases its slot only when no bytes reached DSH and no empty source completed. Once data has reached DSH, an unknown outcome stays reserved until DSH's public API proves the receipt was retired, that is consumed or removed. An acknowledgement of the prompt alone does not release it.
- Before reserving, the host checks the reservations of all sessions (at most 64) and frees those proven retired.
- The reservation for all files of a message is made before the command is durably admitted. If it cannot be made, the request gets 429 and no receipt is created. The staged uploads stay available for a retry with the same requestId.
- At most 16 message dispatches holding attachments run per host at a time. Beyond that the request also gets 429 before admission, with the same retry rules.
- Nothing is resent automatically.

Upload limits:

| Limit | Value |
|---|---|
| Upload size | 20 MiB |
| Staged uploads | 20 per device, 64 per host |
| Non-image staging | ≤64 files and ≤512 MiB in total, including in-flight uploads |
| Image staging | ≤200 MiB of raw image bytes host-wide, from upload until the send completes |
| Staging TTL | 10 minutes; cleanup runs every 30 seconds |
| Concurrent uploads | 2 per device, 4 per host; also applies to send-time uploads to DSH, where the send waits |
| Upload rate | 12 per minute per device |
| Per message | ≤20 attachments and ≤200 MiB |
| Unconfirmed send-time receipts | 20 per session, 64 per host |
| Active admitted dispatches | 32 per host |
| Dispatches with attachments | 16 per host at a time |

Downloads:

- Images and files can be downloaded only if the referencing message is present in the snapshot the device actually receives: within the last 100 messages and after the 2 MiB wire trimming.
- Images (`attachments`): at most 2 concurrent downloads per device and 4 per host.
- Files (`fileDownload`): ≤20 MiB per file and at most 2 concurrent downloads per host.
- Attachment display names longer than 256 UTF-8 bytes are truncated at a code-point boundary instead of failing the snapshot.
- File reference `mime` uses passive DSH metadata or the declared upload type (bounded service-lifetime cache of 4,096 IDs); otherwise `.pdf` maps to `application/pdf` and `.txt`, `.log`, `.csv`, `.md` to `text/plain`. Other files, executable/script/HTML/SVG extensions and active-content types fall back to `application/octet-stream`. The HTTP file download remains an attachment served as octet-stream with `nosniff`.

## Limits and unsupported capabilities

Set explicit limits: JSON request body 64 KiB, input prompt text 32 KiB, latest history 100 messages, complete serialized snapshot/SSE frame 2 MiB, streams 3/device, pairing attempt rate bound. Output message text is never silently byte-truncated. If needed, omit older whole messages and set hasMore with an explicit notice; if the newest single message cannot fit, report payload_too_large rather than corrupting its text. Android applies the same 2 MiB response/frame ceiling. JSON responses of 1024 bytes or more are gzip-compressed when the client sends `Accept-Encoding: gzip`; the 2 MiB JSON limit is unchanged and SSE is not compressed. The HTTP keep-alive timeout is 60 seconds. Upload bodies are raw, not JSON, and follow the separate upload, staging and download limits in [Uploads and attachments](#uploads-and-attachments). Host state contains no transcript cache by default; normalized snapshots are ephemeral. Attachment routes and question responses arrived in host plugin 0.7.0 behind their capability flags. History paging and push registration (`push:false`) will extend this version only after their acceptance slices are implemented. Clients must not render fake controls for false capabilities.

Relay chunks are bounded transport units, not API bodies: a complete snapshot may span many chunks. The 2 MiB limit applies to each JSON response/SSE frame, **not** an SSE connection's cumulative lifetime. Transport close/error releases transient resources and quotas, not host device grants or running DSH work. No automatic byte replay/tunnel migration is provided; a lost final mutation response retains the original uncertain-receipt rules.

## Fixture isolation

A standalone fixture host uses synthetic workspace/session contents and must identify itself as `DSH Mobile demo — not connected to DSH`. Never infer a production test from its successful behavior. A real in-process plugin uses the same protocol and shares actual permitted DSH sessions. Opt-in `--multi-project` demo invitation v1 adds only optional `demoFixture:{markdownSessionId,markdownAnchor,filterWorkspaceId}` synthetic screenshot metadata; pairing/credential fields are unchanged and clients may ignore it. Default fixture invitations omit the extension.
