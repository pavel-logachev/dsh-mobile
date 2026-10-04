# Bounded native opaque-relay acceptance

## Scope

This carrier is an isolated synthetic fixture, not an active DSH profile, deployed VPS service, physical-phone canary or production acceptance. Native UI/repository must remain real. Only the outer relay port is exposed through `adb reverse`; the inner HTTPS host port must never be reversed. The invitation must use `https://h-<routeId>.dsh.invalid`, its exact SAN leaf anchor and mandatory SPKI pin. Successful localhost direct HTTPS requests do not count as relay evidence.

Two distinct future gates:

1. Local debug fixture: loopback `ws://127.0.0.1:<port>` outer transport, opaque inner TLS and synthetic `FixtureAdapter`. Explicitly debug/fixture-only.
2. Release/public relay: reviewed parent-authorized WSS endpoint with ordinary public trust, same pinned inner host authority; no injected outer CA, debug flags, TLS bypass, VPS edits or production profile access by this harness. This gate is blocked until deployment approval and exact operator-owned route provisioning are supplied.

## Implementation ownership

Only new `tools/relay-acceptance*` files and ignored artifacts belong to this worker. Existing host/relay/Android/build/signing source remains worker-owned elsewhere. No unsupported provisional API call is written as if runnable. No emulator, Gradle, signing, listener, connector or VPS operation starts before parent grants its exclusive execution window.

## Existing harness pieces

- [relay-acceptance-adapter.mjs](relay-acceptance-adapter.mjs): wraps the existing public host adapter seam. Admits only `DSH_MOBILE_RELAY_ACCEPTANCE: synthetic opaque relay prompt.` and `DSH_MOBILE_RELAY_LOST_RESPONSE: synthetic receipt reconciliation prompt.` Records synthetic request/session IDs, adapter admissions, snapshots and yielded SSE snapshots. No credentials or private text is logged.
- [relay-acceptance-adapter.test.mjs](relay-acceptance-adapter.test.mjs): unit checks for exact whitelist and armed lost-response behavior. Test doubles are harness-unit checks only, never native relay evidence.

The lost-response injection belongs to the isolated inner host, not the opaque relay. Its request listener registers a synthetic mutation socket; an explicitly armed `beforePrompt` closes that socket at the public adapter boundary after host durable dispatch intent exists. It then allows normal adapter dispatch. This is a dropped mutation response, not a dropped dispatch, replay or TLS rewriting feature. One normal prompt and one loss-stage prompt mean two total adapter admissions; each must independently have exactly one dispatch and original request identity.

## Composition seams required before fixture startup

Confirmed from inspected sources:

- `RelayState(':memory:').provisionRoute()` -> `{routeId,connectorToken}`; route ownership digests are local only.
- Host public `HostAdapter` methods: listPresets/listSessions/snapshot/watch/createSession/prompt/cancel.
- `HostState.createRemotePairing`, `relayGrantSnapshot`, `relayPublication`, `relayStatus`, `listDevices`, `revokeDevice`, `getCommand` exist in developing source.
- `FixtureAdapter`/`FIXTURE` public synthetic constants exist.

Host/relay handoff confirmed and exercised by isolated Node probes:

- `startRelayServer` ready/listen/close semantics, TLS option shape and safe stats shape.
- Connector ownership: whether `createHostServer` starts it, exact `waitPublished`/issue-v2 API, logical inner URL configuration and startup readiness.
- Supported observation of outer mobile access IDs/stream counters without recording header tokens, to prove bootstrap-to-device migration. Access IDs may be compared in memory; receipt should expose booleans/counts rather than secret values.
- Relay pause/disconnect/restart control seam for bounded foreground/offline testing. Grant expiry/revoke must not use general payload interception.

The fixture imports actual Node24 TypeScript sources directly, starts the real host/connector/relay, and waits for host grant acknowledgement. No relay fault/admin endpoint is invented. Local accepted-upgrade counts are retained across revocation; public mode requires separate operator evidence for accepted mobile access IDs because this fixture owns no remote relay counters.

## Native ordered evidence

| Checkpoint | Required observation |
| --- | --- |
| Empty onboarding | Native screen, harmless input; invitation kept in private runtime file and imported through actual system picker. New collapsed manual option may be expanded only via `pairing_manual` if import is unavailable. |
| Pairing | Host v2 invitation, correct logical SAN/pin, acknowledged bootstrap grant. Native Connected only after authenticated catalogs. Record host publication marker complete. Never screenshot/dump raw invitation. |
| Migration | New per-device grant acknowledged; Android's subsequent streams use device access, not bootstrap fallback. Inner paired credential must not be exposed to harness logs. |
| History/create | Select existing `demo-session`, verify literal synthetic history; create in `demo` workspace; read canonical selected chat. |
| Send/live | One click with exact normal prompt; exactly one public adapter prompt admission/request ID; native assistant output; adapter `watch` yields updated authoritative snapshot and relay mobile/host streams increment. |
| Background/foreground | Close observation via Home, reopen real Activity; host task remains un-cancelled; new complete snapshot, no second prompt dispatch. Condition-based UI/native state waits, no blind sleeps. |
| Lost POST response | Explicitly arm local fault, one click with exact loss-stage prompt; inner socket closes after admission. Draft/pending lookup recovers original receipt, one adapter dispatch for that request, no fresh-ID retry. |
| Revocation/offline | Local fixture operator revokes only its synthetic paired device; grant snapshot no longer authorizes new streams and active stream closes; UI not Online, send/create forbidden. Local forget must not imply server revoke. |
| Cleanup | Stop only fixture-owned connector/host/relay; remove reverse and transient invitation/raw XML; private cert/key/config deleted; final receipt retains synthetic results, safe counts, APK/source hashes and native screenshot provenance. No active profile, system trust, security setting or VPS mutation. |

Do not confuse a merely observed assistant final message with proven SSE streaming; require watch/stream evidence. Do not infer exactly-once execution across real upstream from a fixture request count. Do not infer proxy parser/resource limits from UI success: dedicated worker malicious-frame/limit tests provide that evidence.

## Commands and blockers

From project root, harness unit checks and live isolated large-byte probe:

```powershell
node --test tools/relay-acceptance-adapter.test.mjs tools/relay-acceptance-stream.test.mjs
node tools/relay-acceptance-large-probe.mjs
```

The large probe must verify two complete near-2MiB snapshots, cumulative SSE >2MiB and byte-for-byte inner TLS transfer through the actual connector/relay. The synthetic TLS responder proves byte transport only, not host API or native acceptance. Store its receipt in ignored local artifacts and verify cleanup counters and owned-file removal.

Local native fixture, **only after parent grants the emulator window**:

```powershell
node tools/relay-acceptance.mjs --duration-ms 600000 --relay-port 19446 --host-port 19447
```

This prints a nonsecret ready-file path. Native file import uses its `invitationFile`; never print the private file. Only `tcp:19446` may be reversed; `19447` must never be reversed. The real logical inner authority remains `h-<routeId>.dsh.invalid`, not localhost.

Independent Node protocol probe (not native acceptance):

```powershell
node tools/relay-acceptance-probe.mjs artifacts/relay-acceptance/<run>/ready.json
```

The Node probe must verify opaque inner TLS, v2 pairing, per-device migration, create, one dispatch each for normal/lost-response prompts, original receipt recovery and revoke denial. Reconcile its private receipt independently; this is not native UI acceptance.

## Public WSS mode — prepared, not activated

After reviewed deployment and explicit parent authorization, use a **separate temporary operator-provisioned route**, not a production user's route:

```powershell
$env:RELAY_PUBLIC_URL = 'wss://relay.example.com/dsh-mobile-relay' # replace with your reviewed endpoint
node tools/relay-acceptance.mjs --duration-ms 600000 --host-port 19447 --relay-url $env:RELAY_PUBLIC_URL --approved-relay-url $env:RELAY_PUBLIC_URL --route-file C:\\absolute\\private-temporary-route.json
```

The bounded private input must contain exactly `{routeId,connectorToken}`; it is read in memory, never logged/copied to receipts or deleted. Public mode skips the local relay listener, uses ordinary outer WSS trust without custom CA or insecure-loopback escape, and retains inner HTTPS numeric-loopback binding plus exact logical SAN/pin. No adb reverse is needed. The fixture still contains only synthetic conversations and is not actual DSH/production acceptance. APK release signing/trust must be verified separately; the fixture always emits `nativeAcceptancePassed:false` until the parent reconciles real UI assertions/evidence.

Control is a unique local run-directory `control.json` with exactly `{"action":"arm-loss"}`, `revoke-device`, `finish`, or **local-only** `disconnect`. Write atomically through rename; wait for matching `control-result.json`. It is not a remote endpoint. Commands may not contain arbitrary parameters or secrets. `arm-loss` drops the isolated inner mutation response after admission even in public mode. The Node probe supports public WSS only when `ready.publicWss` explicitly enables it, with the exact approved URL, ordinary Node trust, redirects disabled and a 10-second ready deadline. Public migration evidence requires a successful authenticated inner request using the newly returned per-device capability plus acknowledged host publication; remote upgrade counts are labeled `not-observed`, not zero-counter PASS. Local probes retain strict accepted bootstrap/device upgrade counts. Native foreground/reconnect and native bootstrap-retirement evidence remain required. Public disconnect must use reviewed operator/native control, not nonexistent local sockets.

Cleanup closes only owned host/connector/local relay, removes private generated files and transient metadata, and records remote route revocation as **operator required**, never accomplished. The supplied route file remains intact; the operator must revoke the temporary route after testing. Maximum fixture lifetime is 15 minutes; SIGINT/SIGTERM clean up, but forced termination cannot guarantee it. Store machine/run evidence privately. Public probes must independently receive the same exact approved URL through `RELAY_PUBLIC_URL`; `--approved-relay-url` configures the fixture only, not another probe process.
