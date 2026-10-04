# Implementation and acceptance plan

DSH Mobile is a development preview. Source availability and passing local tests do not establish production readiness. Keep exact-build acceptance receipts and machine-specific evidence in ignored private storage, not public documentation.

## 1. Contracts and native vertical slice

- Maintain an inspected DSH controller adapter and explicit exact supported-version allowlist (`0.2.0-rc.2`, `0.2.1-alpha.1`), not invented endpoints or a generic raw RPC proxy.
- Maintain pairing/revocation, read/execute grants, a durable command ledger and public-boundary tests for authentication, duplicates, reconnect and uncertain delivery.
- Keep the Kotlin/Compose client native, with encrypted connection/draft/pending state, foreground observation and explicit recovery.
- Verify host build/tests, Android JVM tests/lint/APKs and synthetic fixture behavior before a release candidate.
- Never resend an uncertain mutation automatically or substitute fake success for an unavailable capability.

## 2. Integration and device acceptance

1. Run [isolated DSH checks](DSH_CANARY.md) before any active-profile change. Deterministic official-model adapter output is not external-model acceptance.
2. Prepare a reviewed TLS identity, explicit grants and fresh one-use invitation using [remote setup](REMOTE_SETUP.md).
3. Review additive plugin activation and undo; do not restart existing work or overwrite unrelated profile entries.
4. Exercise the exact signed APK on an authorized disposable device, then separately on a physical phone over Wi-Fi and mobile internet. Check pairing/trust, history/create/send, foreground return, reconnect, receipt recovery, cancellation and revocation.
5. Confirm closed-app work continues on the host and that logs/screenshots export no private data. Physical-network and Play Protect checks remain distinct from emulator checks.

Until all applicable gates are performed, label the build a development preview. Do not generalize owner-local results to arbitrary infrastructure.

## 3. Optional relay transport

Direct invitation v1 and API v1 receipts remain supported. Remote invitation v2 uses an operator-managed opaque relay: one APK → trusted invitation → confirm host → automatic foreground connection. The computer, DSH, connector and relay must remain available; no separate phone VPN or home port forward is required.

- Follow [relay protocol](RELAY_PROTOCOL.md): exact logical inner host/TLS/SPKI pin, separate outer WSS capabilities and acknowledged per-device publication; never retain bootstrap as a fallback.
- Review [bounded deployment](RELAY_DEPLOYMENT.md) with fresh preflight, pinned identities, independent rollback and a separate temporary canary route.
- Verify wrong trust/pin/name/date, route/splice isolation, ACK/revoke/expiry/restart, malicious framing/queues/stalls, large JSON and long SSE, lost mutation response without redispatch and resource cleanup.
- Separate local debug WS, public WSS, native signed-release and physical roaming evidence. No global availability, metadata invisibility, permanent socket or exactly-once claim.
- Invitations last ≤15 minutes; device relay access initially 365 days then explicit re-pair; setup TLS identity lasts 825 days and requires reviewed rotation.

## 4. Follow-up capabilities

- Structured question replies and one-time approval decisions bound to authoritative pending requests/revision. Ordinary session RPC alone does not safely implement approvals.
- Authorized bounded attachments/downloads, not arbitrary filesystem browsing.
- Optional background notifications with generic payloads and authenticated fetching. Push requires a separate reviewed service configuration; never bake server credentials into an APK.
- Full history paging/search, multiple hosts, app lock and richer model/task detail after core stability.

These are goals, not advertised current capabilities.

## 5. Public repository and release

Keep reproducible builds, generic examples, CI, setup/undo procedures, explicit compatibility, security assumptions and unsupported-feature lists. The project uses the [MIT license](../LICENSE). Scan the publishable tree for credentials, personal paths, transcripts and machine evidence before publication. Commit, push, repository creation and public APK distribution require separate explicit authorization.
