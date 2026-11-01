# Implementation and acceptance plan — 0.9.0

Current source candidate: app **0.9.0 / versionCode 17**, host **0.9.0**, additive API `/v1`. Source availability and passing local tests do not establish release readiness. Releases are not generally labelled development previews; this candidate remains pending the exact-build [release verification](RELEASE_0.9.0.md#verification). Keep machine-specific receipts and physical evidence in private ignored storage.

## 1. Implemented baseline — preserve these contracts

- Native Kotlin/Compose UI, encrypted pairing/drafts/pending commands, bounded stale offline history, explicit reconnect and uncertain-delivery UI.
- Pairing/revocation, independent read/execute grants, narrow in-process controller API and durable per-device command receipts; no arbitrary RPC or filesystem browser.
- Queue/steer/cancel, rename/model selection, answers to agent questions, bounded attachments/downloads. Action approvals and plugin/skill management intentionally remain desktop-only, not incomplete buttons.
- Phone-started task outcome notifications with `promptOutcomes`, encrypted bounded watches/outbox and guarded process/reboot restore; no idle service, permanent connection notification or coverage banner. See [current notification contract](NOTIFICATIONS_0.9.md), not the historical feed design.
- Recovery hold/witness and operator CLI, dispatch cap 32 and ten-minute post-admission deadline → `uncertain`; never silently resend an admitted/uncertain mutation. Epoch is bookkeeping, not fencing or exactly-once.
- Network callbacks wake reconnect, first authorized index page enables online state, stream-first chat and immediate leading-edge assistant text. Physical speed measurements remain a separate gate.
- Exact supported DSH versions: `0.2.0-rc.2`, `0.2.1-alpha.1`, `0.2.1-rc.5`. Historical full/registry canaries support the first two; the current rc.5 full isolated canary has 25 checks. rc.4 is excluded without its own acceptance evidence. [Evidence matrix](DSH_CANARY.md#compatibility-evidence).

## 2. Remaining 0.9.0 release gates

1. Finish independent reviews and any accepted source/test/docs fixes on the release branch. Review the exact diff and privacy scan; do not commit or publish as a side effect of documentation work.
2. With explicit authorization, create reviewed commits, then build from the exact clean release commit/tag. Record that commit, full signed APK/host ZIP/inventory hashes and matching receipts. Same version does not identify different experimental builds; do not relabel an older artifact as built from a later commit.
3. Run host check, Android tests/lint and the full [isolated DSH canary](DSH_CANARY.md) on the final inputs. A deterministic official-model adapter is not external-model or physical-device acceptance.
4. Follow [setup](SETUP.md) for reviewed activation/rollback. Before a permitted install/restart window, reconcile the actual DSH version, private host declaration and profile declaration; the known older host rc.2 declaration must not silently override the rc.5 release target. No automatic live-config write or restart is authorized here.
5. Exercise the exact signed APK/host pair physically: send/answer, queue/cancel, image + non-image file, completion/failure/needs-answer in background and after process death, loss/return of network and Wi-Fi↔mobile, Doze, reboot, before/after speed. Record results only in the [Verification template](RELEASE_0.9.0.md#verification).
6. Preserve failure evidence and explicitly accept or fix remaining limitations before release authorization. Separate local tests, canary, emulator, installed profile and physical phone. Prior-build results do not transfer automatically to new artifacts.

## 3. Optional relay — separate deployment acceptance

Direct invitation v1 and API v1 receipts remain supported. Remote invitation v2 uses an operator-managed opaque relay; the computer, DSH, connector and relay must remain available. No shared public service is provided.

- Follow [relay protocol](RELAY_PROTOCOL.md): exact logical inner host/TLS/SPKI pin, separate outer WSS capabilities and acknowledged per-device publication; no bootstrap fallback.
- Follow [bounded deployment](RELAY_DEPLOYMENT.md) with approved preflight, pinned identities, independent rollback and a separate temporary canary route.
- Verify wrong trust/name/pin/date, route isolation, ACK/revoke/expiry/restart, malicious framing/queues/stalls, large JSON/long SSE and lost mutation response without redispatch.
- Separate local debug, public WSS, native signed-release and physical roaming evidence. No global availability, invisible metadata, permanent socket or exactly-once claim. No router/firewall/VPN or production service changes follow from this plan.

## 4. Backlog — not promised by 0.9.0

- Optional reviewed push path (for example UnifiedPush), without FCM credentials or a permanent idle socket disguised as push.
- Bounded full-history paging/search, multiple hosts, app lock and richer task/tool detail.
- Any mobile approval action only after an inspected supported upstream API and separate contract/acceptance. Plugin/skill desktop parity is not the native companion's current goal.

## 5. Publication hygiene

Keep generic examples, reproducible build commands, explicit compatibility and honest limitations. The project uses [MIT](../LICENSE). Scan the tracked and proposed new files for personal paths, real network addresses, credentials and private chat text. Keep exact private diagnostics outside Git. Commit, push, tags, release assets and public publication require separate authorization; this plan performs none of them.
