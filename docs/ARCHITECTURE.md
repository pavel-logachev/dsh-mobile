# DSH Mobile architecture — initial implementation contract

## Decision and scope

Build a native Kotlin/Jetpack Compose Android app and a small optional in-process Cordis companion plugin for the existing DSH host. The plugin uses inspected DSH controllers; it does not scrape the web UI, export a browser launch token, replace DSH, or create a separate agent runtime. The Android app depends on our versioned mobile contract, not on DSH's internal wire format.

```
Android UI / ViewModel / repository
        | HTTPS + authenticated SSE
DSH Mobile host plugin: pairing, grants, command ledger, projection
        | direct in-process controller adapter
Existing DSH sessions / agent / persistence / tools / subscriptions
```

The raw DSH Web port remains unchanged and private. Direct invitation v1 remains the default transport and requires a reachable HTTPS route. An **optional built-in opaque relay transport v0.2** uses an operator-managed relay without an external phone VPN, home port forwarding or internet exposure of DSH. Deployments and phone acceptance require separate operator checks. Debug-only exact-loopback HTTP supports adb reverse for emulator acceptance.

Remote topology: Android → outer WSS → operator relay ← outbound WSS desktop connector → fixed loopback companion HTTPS. The relay forwards inner TLS bytes, not mobile API requests. Android uses a private authenticated CONNECT proxy accepting only `h-<32hex>.dsh.invalid:443`; normal inner certificate trust/date/exact-hostname validation and the mandatory SPKI pin stay end-to-end. Outer WSS uses separate public trust and route capabilities. The connector never accepts a peer-selected target. See [the authoritative relay contract](<RELAY_PROTOCOL.md>).

The relay can observe IPs, route/capability identifiers, timing, sizes and possible TLS handshake metadata; it can deny service. Device bearer and HTTP/chat contents remain inside verified inner TLS. Bootstrap access is replaced by a per-device relay grant after acknowledged pairing; it is never a permanent fallback. Phone credentials stay in encrypted local state. Transport retries do not replay command bytes or change receipt reconciliation.

## Ownership

- **DSH** owns actual sessions, messages, model execution, history and tool permissions.
- **Companion host** owns device grants, one-time pairing offers, revocation and command receipts. It exposes an explicit small set of operations; it is not a generic RPC proxy.
- **Android** owns presentation, paired-host credential storage, drafts and delivery-state UI. Closing an Activity only closes observation; it never cancels host work.

## Integration evidence

Installed DSH 0.2.0-rc.2 exposes `ctx.sessionController` with list/create/prompt/cancel/page/follow/projections. `follow` starts with a snapshot, then durable events and optional process-local assistant stream frames. Prompt carries a client requestId. Browser auth uses an authority-bound cookie derived from a launch token; no mobile auth grant was found. The stdio SDK creates a separate runtime and is not the integration for shared existing chats.

Evidence paths are documented generically under `node_modules/@deepseek-ai/dsh-api-session-controller/lib/types/` and `dsh-client-connection/lib/types/browser-auth.d.ts`; machine-specific absolute paths are deliberately not committed. Installed compiled JS and declaration files, not just package metadata, were inspected. Live composition remains an acceptance requirement. Adapter activation requires an explicit owner-declared DSH version from an exact allowlist of inspected releases (`0.2.0-rc.2`, `0.2.1-alpha.1`; see [SETUP](SETUP.md)) and refuses others before exposing observation/mutations. This declaration is not automatic host-version detection; the operator must revalidate after upgrading DSH.

## Host security boundary

- Default bind is loopback. A non-loopback bind requires explicit TLS configuration. No insecure production fallback.
- Pairing is initiated by a local operator CLI. Offers use at least 256 random bits, expire quickly, are single-use, and are stored hashed. Successful pairing issues a separate random bearer credential scoped to one device; only its hash is stored on the host.
- A connection invitation includes server URL and certificate public-key pin, not a DSH credential. The Android client verifies the pinned host identity before sending the pairing secret. Standard hostname and certificate validity checks remain required.
- Tokens are never in query strings, logs, crash messages or SSE IDs. Device credentials are wrapped with Android Keystore and excluded from backup.
- A local SQLite state database provides transactional pairing consumption and a persistent command ledger. A separate stable adjacent SQLite lock database in non-WAL mode holds an exclusive transaction for the host lifetime, released by the OS on process exit; no PID-only stale-lock assumption. Acquire ownership before recovery/admission, release only after dispatches are settled or safely marked uncertain, and never unlink/replace the coordination file while an owner may exist. This is local-filesystem coordination, not a distributed/network-share locking claim. Credentials/configuration, certificates and the database stay outside Git. Use Node's built-in SQLite with a documented supported Node floor.
- Workspace source is owner-selected: legacy explicit `{id,name,path}` roots (default), or `workspaceSource:"dsh-registry"` with no nonempty explicit list. A shared live source calls `ctx.workspaceRegistry.list()` in durable sidebar order, maps DSH UUIDs/titles, caps the first 100 registrations, and omits missing directories entirely. Registry archive IDs are hidden from reads and mutations. Registry creation uses SessionController's inspected `workspaceId` lane to attach new sessions to the DSH sidebar; legacy explicit-list creation retains canonical `cwd` (the two upstream fields are mutually exclusive). Workspace identity is exact `fs.realpath` string equality, matching installed DSH even on case-sensitive Windows directories; no unconditional case folding. Listing deduplicates cwd resolution only inside one request (bounded to 1024 entries), so a later request cannot reuse a stale authorization binding. Snapshot headers and mutation paths resolve uncached. Adapter mutation admission also synchronously verifies that the observed registry revision is still current after filesystem preparation and cold iterator cleanup. After awaited directory status checks, the source verifies the same active underlying registry Service identity and exact visible IDs/paths/titles/order; concurrent changes fail closed rather than returning stale registrations. Cordis 4.0.4 produces fresh `ctx.get()` tracing proxies, so identity alone is normalized through its inspected `cordis.original` symbol, never by caching a proxy or bypassing the active lookup; method calls retain the traced caller context.
- An operator may explicitly choose **read and execute in all current and future DSH projects**. Local grants use `['*']` for each permission; execute `*` requires read `*`. Explicit grants remain supported in explicit-list mode, and execute must stay within read scope. The local `grant` command transactionally replaces only an active device's grants, not credentials, relay capabilities or receipts. Standalone registry-mode administration accepts `all` only and never reads DSH storage. No remote arbitrary cwd/path, settings changes, provider secrets, terminal proxy or arbitrary RPC access.
- **Workspace filtering is not a sandbox.** A trusted DSH agent may already have broader permissions and a transcript may contain cross-workspace material. The phone owner receives the permitted transcripts as-is. Do not advertise security isolation based only on cwd.
- Re-check authentication/grants for every request and periodically during streams. Host dispatch is the authorization linearization point: revocation blocks later admissions and closes observations, but does not retroactively cancel a command already dispatched to the trusted adapter (including its asynchronous preparation). The server passes its authorized `expectedWorkspaceId` into every adapter mutation; a fresh session mapping to a different registry ID is rejected before upstream prompt/cancel/create, independently of post-entry grant revocation semantics. Revocation/read-scope narrowing closes existing streams on periodic recheck (normally ≤1 s plus source I/O); execute-only narrowing republishes `canExecute:false` even while idle. Narrowing never retroactively cancels already-dispatched work. Body size, stream count, rate and pagination limits are bounded. Origin-bearing browser requests are rejected unless specifically supported; no permissive CORS.

## Delivery and recovery

Every mutation has a client-generated UUID requestId. The host stores (deviceId, requestId, operation, canonical payload hash, result/status) before dispatch. An identical repeat returns the same receipt; a different payload using the same identity returns 409. Persist `dispatching` before touching DSH. On uncertain upstream outcome or a crash in the dispatch window, preserve `uncertain`; do not silently redispatch. DSH requestId reconciliation may strengthen this later, but no exactly-once promise is made across an unverified upstream boundary.

Android persists the requestId/draft before transmission and distinguishes sending, accepted, failed and uncertain delivery. A network timeout is not proof of failure. Reconnection looks up the original receipt and reloads the canonical conversation. Explicit uncertain-state UX prevents duplicate side effects.

The installed rc.2 follow implementation can promote a cold session after yielding its opening snapshot. Therefore observation must close the iterator after the opening frame; never advance a cold follow merely to observe. The adapter combines cold-safe opening snapshots with scoped host event subscriptions/periodic refresh, avoiding read-triggered model execution.

Observation uses SSE with a full bounded normalized snapshot first and subsequent replacement snapshots. On reconnect the client starts a new observation and replaces its view with an authoritative snapshot. This intentionally avoids pretending that an event cursor survives every plugin/host generation. History paging uses the corresponding DSH snapshot cut. Live assistant text is provisional until a durable assistant record replaces it.

## First release slice

Pair/import invitation, host connection status, workspace/preset selection, permitted sessions, bounded readable conversation history, new session, text prompt, live observation, stop active turn, reconnect/resync, local sign-out and remote device revocation. Native Material 3 UI, Russian/English resources, system light/dark, IME/safe-area support and font scaling.

## Explicit later capabilities

Attachments/downloads, structured questions/approval actions, richer tool/subagent detail, optional FCM notifications, multiple hosts and full offline history are separate slices. Capability negotiation must hide unavailable actions. In particular, no invisible permanent background socket and no background-completion notification claim until a push path is configured and exercised.

## Validation gates

1. Public-boundary host tests: expired/reused pairing, unauthorized reads, revocation, read/execute grants, duplicate/mismatched command IDs, crash/uncertain semantics, bounded streams and reconnect snapshot.
2. Adapter fixtures reflecting inspected DSH event/projection shapes; compile/load compatibility with the installed Cordis API. Fixtures are synthetic and labelled.
3. Android unit tests for connection validation, pin enforcement, reducer/reconnect and delivery-state behavior; actual debug APK build and lint.
4. Emulator acceptance using an explicitly labelled fixture host, then isolated real DSH integration without changing active production settings. A fixture pass never counts as live DSH acceptance.
5. Independent security/correctness review and native screenshots in light/dark and increased font scale.
6. Physical phone Wi-Fi→mobile acceptance over the chosen direct HTTPS route or public opaque relay is a separate final gate requiring device/network access. v0.2 additionally needs wrong trust/pin/name/date, malicious relay/framing, grant publication/revoke/expiry, large JSON/long SSE, uncertain receipt and native release foreground/reconnect/resource-cleanup checks. Until passed, label the release a development preview, not daily-use production-ready.

## Rollout

[Remote setup](<REMOTE_SETUP.md>) prepares private state without activating the profile; [relay deployment](<RELAY_DEPLOYMENT.md>) governs an additive public service. Require independent review, live preflight, rollback protection and a canary for each rollout. Infrastructure permission does not imply permission to restart ongoing DSH work, reconfigure a home network or publish a repository. Release acceptance must identify the exact build and distinguish unit, fixture, emulator and physical-device results.
