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

- `GET /capabilities` → `{protocolVersion:1, hostName, upstreamVersion, capabilities:{sessions:true, textPrompt:true, cancel:true, liveSnapshots:true, attachments:false, questions:false, approvals:false, push:false}}`.
- `GET /workspaces` → `{items:[{id,name,canExecute}]}`. IDs map to owner-selected canonical roots: configured opaque IDs in legacy explicit-list mode, or DSH workspace UUIDs in live registry mode. Registry order matches the sidebar, names are sanitized, missing directories are omitted and the first 100 registrations bound the scope. No raw paths in client responses. Permission policy is host-local; wildcard `['*']` grants cover current/future source workspaces without any new mobile request field.
- `GET /presets` → `{items:[{id,name}]}`. Empty list means host default only.
- `GET /sessions?workspaceId=<optional>&limit=50&cursor=<optional>` → `{items:[Session],nextCursor:null|string}`. Maximum limit 100; nextCursor opaque validated cursor. Filter unauthorized sessions before pagination. Exclude subagent sessions and, in registry mode, archived sessions before pagination. Each request resolves distinct cwd bindings anew; no cross-request cached workspace identity may authorize metadata.
- `GET /sessions/{id}` → `Snapshot` (bounded latest history, max 100 messages). Cold read, do not resume execution merely to read.
- `GET /sessions/{id}/events` → SSE stream. Send `event: snapshot` with a complete `Snapshot` on open and after material changes; heartbeat comments at most 20 s apart. The first snapshot on every connection replaces client history, including after plugin restart. Bounded active streams/device. No bearer in URL, no Last-Event-ID replay guarantee. Close revoked/unauthorized clients or streams whose registry entry/read grant is removed. Grant rechecks also republish changed `canExecute`, even on idle conversations; grant replacement is local administration, not a new remote endpoint. No lossless low-level event claim.
- `GET /commands/{requestId}` → `CommandReceipt`; only the issuing device can read its own receipt; 404 means no persisted receipt known, NOT proof that an arbitrary upstream task never ran.

```ts
type Session = {
  id: string; title: string; workspaceId: string; updatedAt: number;
  running: boolean; canExecute: boolean;
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
  notice?: string;
  activityDetail?: { turnStartedAt: number; tool?: string };
};
```

History must respect DSH surface replacements; do not expose discarded intermediate assistant attempts as final answers. Replacement endpoints refer to current surface order, not numeric sequence ranges. If a bounded history cut lacks an endpoint and the surface cannot be proven, fail closed with activity `unknown`, an empty message surface and a desktop notice rather than retain potentially discarded answers. Some compacted/replaced histories may therefore require the desktop until full history reconstruction/paging is implemented. Hidden/tool/unknown event types are not dumped as raw JSON. Known-but-not-yet-supported question/approval states must show a neutral notice to return to the desktop, not silently present an idle task.

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

Additive API v1; existing command receipts, TLS pins, relay wire and `push:false` are unchanged. No UnifiedPush/FCM/WorkManager/boot receiver. Capabilities add `notifications:boolean` (feed implementation available; opt-in starts baseline asynchronously) and `notificationCapabilities:{version:1,feed:boolean,unifiedPush:false,coverage:"initializing"|"ready"|"degraded",retentionMs:604800000}`. Coverage is conservative: a source gap stays degraded until a new validated startup; this is not lossless replay.

- `GET /notification-settings` returns `{revision,enabled,projects:[{workspaceId,enabled}],chats:[{sessionId,enabled}]}`. Default master off. Both alert kinds share one boolean: chat override > project override > true, gated by master and current read grants. GET prunes unauthorized/stale overrides and increases revision when changed.
- `PUT /notification-settings` accepts the same resource with `expectedRevision` instead of `revision`. Full replacement, changed policy increases revision; identical retry at current/previous revision is idempotent; stale different policy is 409. Strict keys/booleans/unique IDs, ≤100 projects/256 chats/32 KiB. Unauthorized IDs are hidden 404. Preferences do not grant execution.
- `GET /notification-events?after=<optional>&limit=50` returns `{version:1,epoch,items,pending,nextCursor,hasMore,resetRequired,coverage}`. Limit 1–100, scan ≤400. `items` contain only `{version:1,eventId,sequence,occurredAt,expiresAt,workspaceId,sessionId,kind,sourceSeq,turn?,attentionId?}`; kinds are `answer-finished`, `attention-needed`, `attention-cleared`. UUID event/episode IDs, safe sequence, metadata only (no titles, transcript, arguments or approval reason). Current source mapping/archives/read grants filter every page. Pending reset records are `{sessionId,workspaceId,attentionId}`, max 1,000, silent recovery only.
- `GET /notification-events/stream?after=<optional>` sends only `event:notification-page` with that same page and `id:nextCursor`, not the proposal's three separate live event types. It drains bounded pages via a one-second recheck and sends heartbeat comments every 15 seconds. `Last-Event-ID`, if supplied, must equal `after`. One notification stream/device within 3/device and 32 total; ≤128 KiB frame/output budget, close slow writers and replay via cursor.

Missing/pruned/wrong-epoch cursor resets at the current journal head with no completion backlog and a consistent inline pending cut. Device MAC failure/malformed cursor is 400; cursor is canonical base64url of `[epoch,sequence,mac]` (≤256), HMAC-SHA256 binds the authenticated device, epoch and sequence; it is not a credential. Journal/key/producer watermarks persist in the existing SQLite state. Up to 2,000 events/device for seven days; alert display expires after 24 hours. Gaps/producer rate overflow reset epoch, recover current pending silently, never invent missed success. Master/scope changes also reset the pending view. GET retention pruning is lazy; dormant records are pruned at next delivery/read, not by a dedicated vacuum worker.

Additional limits: GET 60/min/device, stream opens 6/min/device, writes 6/min/device within existing 120 total; 429 `Retry-After:60`. Source entries 30/min/session, 120/min/device, 1,000/min/host; 64 enabled devices; 4,096 source envelopes/10,000 active sessions. Queue/capacity loss degrades coverage. Every route requires the existing bearer; no tokens in URL/SSE IDs. The phone service owns a separate pinned read-only transport, atomically persists dedupe+cursor before display and never replays an agent mutation.

## Mutations and durable receipts

The wire bodies are unchanged. Internally the server binds each action to its authorized workspace ID; the adapter rechecks fresh scope and rejects a different workspace mapping before calling DSH. Registry-archived sessions are not valid prompt or cancellation targets. Grant revocation after adapter entry still does not retroactively cancel already-dispatched work.

- `POST /sessions` body `{requestId, workspaceId, presetId?:string}` → CommandReceipt; accepted receipt result `{sessionId}`.
- `POST /sessions/{id}/messages` body `{requestId, text}` → CommandReceipt. First slice sends in DSH queue mode; no hidden automatic steer. Reject empty/oversized text. DSH gets the original requestId for correlation.
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

Use UUID requestId. `pending` → HTTP202; accepted → HTTP200 (create may use 201); `uncertain` → HTTP202; a known rejection → appropriate 4xx with a persisted rejected receipt returned as `CommandReceipt`. Syntax/auth failures before command admission use the error envelope. Same device/requestId + same canonical action/payload returns original receipt without redispatch; same ID with different payload returns 409 error envelope. Persist dispatch intent before invoking upstream. Adapter resolution means DSH controller admission, never completion of the model/task; a later execution failure cannot retroactively make an accepted command rejected. Only a known pre-admission validation/domain rejection yields rejected. A generic error/abort after dispatch is uncertain because upstream side effects may already have occurred. A server-start-owned recovery converts abandoned dispatches to uncertain; merely opening the local admin database must not change active receipts. Never auto-resubmit uncertain upstream work.

Mobile sends once per explicit user action, stores the ID/draft before sending, and queries the receipt on network failure/reconnect. It must not create a fresh ID merely because a response was lost. Show `uncertain` explicitly; keep its draft/receipt available across process recreation. Surface canonical user messages carrying requestId to reconcile accepted optimistic messages. If a receipt stays uncertain or 404, offer an explicit local abandonment after the owner checks the host: warn that work may already have run, clear the local pending record and associated send draft, and never resend or cancel as a side effect. Keep this distinct from a known rejection. No promise of exactly-once execution across unverified DSH persistence.

## Limits and unsupported capabilities

Set explicit limits: JSON request body 64 KiB, input prompt text 32 KiB, latest history 100 messages, complete serialized snapshot/SSE frame 2 MiB, streams 3/device, pairing attempt rate bound. Output message text is never silently byte-truncated. If needed, omit older whole messages and set hasMore with an explicit notice; if the newest single message cannot fit, report payload_too_large rather than corrupting its text. Android applies the same 2 MiB response/frame ceiling. Host state contains no transcript cache by default; normalized snapshots are ephemeral. Rich message rendering, history paging, attachment routes, question responses and push registration will extend this version only after their acceptance slices are implemented. Clients must not render fake controls for false capabilities.

Relay chunks are bounded transport units, not API bodies: a complete snapshot may span many chunks. The 2 MiB limit applies to each JSON response/SSE frame, **not** an SSE connection's cumulative lifetime. Transport close/error releases transient resources and quotas, not host device grants or running DSH work. No automatic byte replay/tunnel migration is provided; a lost final mutation response retains the original uncertain-receipt rules.

## Fixture isolation

A standalone fixture host uses synthetic workspace/session contents and must identify itself as `DSH Mobile demo — not connected to DSH`. Never infer a production test from its successful behavior. A real in-process plugin uses the same protocol and shares actual permitted DSH sessions. Opt-in `--multi-project` demo invitation v1 adds only optional `demoFixture:{markdownSessionId,markdownAnchor,filterWorkspaceId}` synthetic screenshot metadata; pairing/credential fields are unchanged and clients may ignore it. Default fixture invitations omit the extension.
