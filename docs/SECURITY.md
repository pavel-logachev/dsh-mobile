# Security and reporting

DSH Mobile is an independent development preview. This document describes the intended boundary and operating assumptions; it is not a security certification or a claim that all acceptance gates have passed. See [Architecture](ARCHITECTURE.md), [Protocol](PROTOCOL.md) and [the acceptance plan](PLAN.md).

## Trust assumptions

- The adapter requires the owner's explicit `dshVersion: '0.2.0-rc.2'` declaration; it does not detect the installation automatically. This is the only declared supported version. A version declaration is not live compatibility evidence.
- The owner trusts the host computer, its DSH installation, configured model/providers and the granted device. Host compromise is outside the protection offered by a phone client.
- Android Keystore protects stored credentials; it is not a guarantee against an unlocked, rooted or compromised device. A trusted user's screenshots, keyboard and screen sharing can disclose chat contents.
- The companion exposes a narrow session API, not arbitrary RPC, a terminal or a filesystem browser. Read and execute grants are distinct. An operator can explicitly choose permissions for **all current and future registered DSH projects**, represented by `['*']` grants. This intentionally broad trust is not a least-privilege default; explicit-list scope remains available.
- **Workspace filtering is not a sandbox.** A DSH agent may already have wider tool permissions, and a permitted transcript can contain material from outside its working directory. Do not treat a workspace grant as isolation from all other host data.
- The companion must not export provider credentials, DSH browser launch tokens, subscriptions or raw host paths to the client.

## Transport and pairing

Production requires HTTPS, normal certificate validity/hostname verification and the invitation's SHA-256 SPKI pin. An invitation may carry a single explicit certificate trust anchor; this does not authorize trust-all TLS or a permissive hostname verifier. Pairing is initiated locally, is short-lived and single-use, and yields a separate device credential. Treat both invitations and device credentials as secrets.

Direct v1 defaults to loopback; a non-loopback companion bind requires explicit TLS and an operator-provided reachable route. Optional remote v0.2 uses an operator-managed public opaque relay, not a phone VPN or home port forwarding. Both endpoints initiate WSS; the companion remains on one fixed numeric-loopback TLS target. **Never publish the raw DSH Web port or turn the relay into a general-purpose TCP proxy.** Infrastructure approval is explicit and scoped; setup does not automatically deploy, restart DSH or change router/VPN/firewall.

Inner TLS terminates only at Android and companion. A private authenticated CONNECT accepts only the exact `h-<32hex>.dsh.invalid:443` authority; trust/date/hostname and SPKI pin checks apply before inner secrets. Outer WSS uses independent public trust and capability headers, with no redirects. Relay credentials are not API pairing secrets/device bearers. The relay sees IPs, routing/capabilities, timing/sizes and possible handshake metadata and can deny service; it is not trusted to authenticate the host. [Relay protocol](<RELAY_PROTOCOL.md>) specifies framing, queue, admission and lifetime bounds.

Only debug Android builds allow cleartext, and only to exact `127.0.0.1` or `localhost` for a labelled local fixture/bridge via `adb reverse`. This is not a production transport mode. Never disable certificate checks or embed test trust in release.

## Credentials, grants and revocation

The host stores credential hashes, not plaintext bearer tokens. Relay/host SQLite stores capability digests, IDs, binding and expiry; the independent connector credential stays in private owner-only host configuration, never in a phone invitation. Device grants and revocation are checked at request boundaries and during observation; outer grant revocation complements, not replaces, inner host authorization. Phone bearer and relay access are atomically stored inside Keystore-protected encrypted state and excluded from backup/transfer. After pairing, bootstrap is retired: recovered state must not fall back to it. Host state, certificates and signing keys stay outside Git.

Registry mode resolves the active Cordis registry on each use and fails closed if unavailable; it never discovers arbitrary folders or reads registry storage from the CLI. Missing directories are omitted entirely, archived sessions are hidden, and at most the first 100 sidebar registrations are exposed. Titles are control-character-free and bounded to 128 UTF-8 bytes. Directory status checks are asynchronous: before returning, the source rechecks active service identity and the visible registry's exact IDs/paths/titles/order, failing closed on replacement, loss or concurrent revision. Canonical paths compare by exact `fs.realpath` output, never by Windows-wide case folding. Listings deduplicate cwd only within the current request, never across requests; opening/mutation checks remain uncached. Each mutation also carries the server-authorized workspace ID into the adapter, which rejects a different fresh mapping before upstream admission and synchronously validates the observed registry revision after filesystem awaits/iterator cleanup. These checks do not remove filesystem TOCTOU or broadened agent-tool permissions.

`grant --config ... --device ... --read all --execute all` changes an existing active device without re-pairing. Replacement is a SQLite transaction updating only grants; tokens, relay grants and command receipts are unchanged. Omit `--execute` to remove execution. Execute `*` requires read `*`; explicit execution must be a subset of read. Registry-mode CLI accepts `all` only (or omitted execution); explicit IDs require explicit-list mode. Streams periodically close on removed read scope and republish `canExecute` on removed execution; already-dispatched work is not cancelled.

Remote invitations expire within 15 minutes; initial per-device relay access expires in 365 days and requires explicit re-pair until rotation exists. Setup TLS identity has 825-day validity; expiry/key/certificate changes require reviewed rotation, never silent regeneration or trust bypass. Lost pairing response retains inspect/revoke/new-offer recovery, not consumed-token replay.

Local **forget/sign-out deletes the phone's credential only**. When the host is offline or unreachable, it cannot establish that a credential has been revoked remotely. Revoke through the local host administrator or an authenticated supported revoke operation when reachable; verify the host's revocation result. A lost phone should be revoked on the host without waiting for it to reconnect. Closing the Activity or disconnecting a stream does not cancel running host work.

## Side effects and uncertain delivery

A timeout is not proof that a prompt or cancellation failed. The original request ID and durable receipt are used for reconciliation; uncertain commands must not be silently replayed under a new ID. Cancellation is a request, not immediate proof of stopped execution. There is no exactly-once execution guarantee across an unverified upstream persistence boundary.

## Bounded history integrity

A bounded snapshot may omit the endpoints needed to resolve message replacements. The adapter fails closed in that case instead of displaying potentially discarded or unverified history. Some compacted conversations require the desktop until paging is available. This is a deliberate integrity limitation, not evidence that the conversation is empty or that all history was synchronized.

## Safe diagnostics

Do not share pairing invitations, bearer tokens, authorization headers, provider keys, cookie/launch tokens, private transcripts, database files, Keystore material, private TLS/signing keys, personal paths or real network addresses. Avoid full HTTP bodies, query strings, raw SSE dumps and unsanitized crash logs. Diagnostic archives and screenshots must be inspected and scrubbed before sharing; ignored files are not automatically safe to disclose.

Use synthetic fixtures and fresh disposable grants for reproductions. Identify whether a result came from unit tests, a labelled fixture, an emulator, an isolated real DSH canary or a physical phone. Never report fixture success as production acceptance.

## Responsible reporting

Do not put exploitable details or secrets in public issues. Contact the project maintainer privately through a channel you already know. No public security-reporting contact or dedicated intake URL has been established yet; the owner must provide one before public release. If no private channel is available, ask for a private reporting route without posting the vulnerability details.

A useful private report includes affected versions, the trust boundary crossed, a minimal synthetic reproduction, expected/observed behavior and possible impact. Send only scrubbed diagnostics. No response-time or remediation SLA is promised during this preview.

## Release prerequisites

Independent security/correctness review, real DSH compatibility, public relay/native release acceptance and physical Wi-Fi/mobile transitions remain separate gates. Local tests do not prove deployment or a usable remote connection. Deployment requires operator authorization, reviewed rollback/preflight and a canary. Profile activation, certificate rotation and device rollout follow reviewed operator procedures. Attachments, questions, approvals and push remain unavailable. The project uses the MIT license; a dedicated private security contact has not yet been established.
