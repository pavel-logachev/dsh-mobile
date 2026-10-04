# Built-in remote transport — protocol draft 0.2

Status: development-preview transport. This is a new transport under the existing host API, not a change to execution/receipt semantics. Deployment and physical-phone acceptance remain separate. The existing direct invitation v1 stays supported.

## Trust and topology

Android and the desktop connector initiate WSS connections to an operator-owned public relay. The relay forwards **opaque inner TLS bytes**. Existing HTTPS certificate trust, validity, hostname checks and mandatory SPKI pin terminate only at Android and the companion host. No relay token is an API pairing secret or device bearer. No new cryptographic protocol is invented.

The companion remains bound to numeric loopback on one fixed TLS port. The connector may connect only to that configured port, never a relay-provided destination. The logical inner authority is `h-<32 lowercase hex>.dsh.invalid:443`, with that exact certificate DNS SAN, a trusted invitation certificate and pin. It never needs public DNS. The relay sees outer capabilities, IPs, routing identity, timing/sizes and possible inner TLS handshake metadata, not HTTP messages, device bearer or conversation contents. A malicious relay can deny service; it cannot be trusted for inner authentication.

Android runs an ephemeral IPv4 loopback HTTP CONNECT proxy per transport owner. It requires a random private proxy credential, accepts only the exact inner authority, and never forwards ordinary HTTP or arbitrary CONNECT requests. The inner OkHttp client uses this explicit proxy and retains its normal SSL socket, hostname verification and pin. A separate WSS client uses ordinary public trust, no inner private trust manager, redirects disabled, and no global/system proxy side effects.

## Invitation v2 and pairing

```json
{
  "version": 2,
  "baseUrl": "https://h-<32hex>.dsh.invalid",
  "pinSha256": "sha256/<base64>",
  "certificatePem": "<one host leaf certificate>",
  "pairingToken": "<existing one-use host secret>",
  "expiresAt": 0,
  "relay": {
    "url": "wss://<operator-origin>/<optional-prefix>",
    "routeId": "<32hex>",
    "accessId": "<UUID>",
    "accessToken": "<32-byte base64url>"
  }
}
```

`expiresAt` is a positive epoch-millisecond invitation deadline, at most 15 minutes from issuance. URLs reject userinfo/query/fragment/backslash/dot-segment normalization tricks; optional relay path prefixes are canonical and bounded. Append the protocol paths below to that prefix. WSS is required in release. Explicit test-only WS loopback is never accepted by release.

A bootstrap relay grant is separate from the inner pairing offer, is locally bound to that exact pairing-offer digest, expires at the invitation deadline, and permits at most two simultaneous TLS streams. Invitation issuance atomically persists both, waits for publication acknowledgement on the current ready connector generation, and only then reports the invitation as ready; unavailable control must fail safely without printing an apparently usable invitation. It is not consumed by the first socket; the host pairing token remains one-use. On successful inner `POST /v1/pairings`, the response additionally includes `relayAccess: {accessId,accessToken,expiresAt}`. The host creates a per-device grant atomically with pairing, publishes its digest to the relay and waits for acknowledgement before returning 201. Publication has a 5-second acknowledgement deadline and a durable pending-publication marker. If it fails, revoke the new device/grant and fail safely; the consumed invitation must be reissued. Runtime startup revokes abandoned pending-publication devices/grants before accepting relay pairings. Mark publication complete only after acknowledgement of the exact snapshot containing the grant; a later lost HTTP response retains the existing orphan-device recovery rule. Lost pairing response follows existing inspect/revoke/new-offer recovery, never token replay recovery.

Android validates and atomically stores the new relay access with the device bearer, then retires the bootstrap transport and uses the per-device grant. It must not silently keep bootstrap credentials as a permanent fallback. Device grants have a declared expiry (initial policy: 365 days); expiry requires explicit re-pair until rotation is implemented. Bootstrap grants expire naturally even after pairing. No plaintext transport credentials are stored in host/relay SQLite: only digests, IDs, binding and expiry. The phone stores its own required plaintext capabilities only inside its existing encrypted state.

## Relay ownership and endpoints

Initial route provisioning is a local relay-operator CLI, not an anonymous public registration service. Relay durable state stores route ID and SHA-256 of an independent 256-bit connector credential. The host keeps its connector credential in private owner-only configuration. Connector credential never appears in phone invitations.

All WebSocket upgrade credentials use request headers, never query strings, subprotocols or logs. Reject Origin/Sec-Fetch browser requests. No redirect following.

- `GET /v1/control`: headers `Authorization: Bearer <connector secret>`, `X-DSH-Route: <routeId>`; host control connection.
- `GET /v1/mobile`: headers `Authorization: Bearer <relay access token>`, `X-DSH-Route`, `X-DSH-Access: <accessId>`; one mobile data socket per inner TCP socket.
- `GET /v1/host`: headers `Authorization: Bearer <connector secret>`, `X-DSH-Route`, `X-DSH-Stream: <UUID>`, `X-DSH-Join: <single-use 32-byte token>`; matching host data socket.
- `GET /healthz`: minimal process health without routes, credentials or user data.

A second control connection for an already-live route is rejected, not allowed to seize it. Relay assigns a new random generation on each accepted control connection. On control loss, all old-generation pending/data streams close and grants are cleared. New mobile opens wait until an acknowledged grant snapshot is installed. A single connector-owned publisher serializes full snapshots, with at most one in flight and newer state queued/coalesced. An ACK is bound to its requestId and current generation; grant waiters require a matching committed snapshot containing their grant. Control loss rejects all outstanding ACK waiters. Persist revocation before publication so stale refreshes cannot reintroduce revoked grants. Expiry denies new opens and closes active grant streams immediately, not only on periodic refresh. Relay restart has no live streams/grants; the authenticated connector resynchronizes host-owned state.

## Control and data wire

Every application WebSocket message is **one unfragmented binary message**, at most 32 KiB. No TEXT messages, CONTINUATION frames, non-FIN data frames or permessage-deflate. JSON controls are UTF-8 binary messages and parsed only in their defined phase. WebSocket ping/pong/close obey separate small-frame/rate limits. HTTP upgrade headers are capped at 8 KiB.

Host control messages:

- relay → host: `{type:"hello",version:1,generation:"<UUID>"}`.
- host → relay: `{type:"grants",version:1,requestId:"<UUID>",grants:[{accessId,tokenHash,deviceId:null|"<UUID>",expiresAt,maxStreams:2|8}]}`; complete authoritative snapshot, at most 64 grants. Expired/revoked grants are omitted. Hash is canonical lowercase SHA-256 hex.
- relay → host: `{type:"ack",requestId:"<UUID>"}` after atomic snapshot replacement and closing streams whose grants disappeared/changed.
- relay → host: `{type:"open",streamId:"<UUID>",joinToken:"<32-byte base64url>",generation:"<UUID>"}`.

Mobile/host data rendezvous:

1. Mobile upgrade authenticates a current grant and reserves bounded pending state.
2. Relay notifies the current connector via `open`.
3. Connector authenticates `/v1/host` with its credential AND the unpredictable, single-use join token bound to route/generation/stream/mobile socket.
4. The connector first opens its fixed loopback target. Both data sockets then have binary JSON `{type:"ready",version:1}` enqueued through serialized writes before any TLS chunk can be forwarded. Only then can either send binary inner TLS bytes.
5. Each subsequent binary message is an arbitrary raw TLS chunk of at most 32 KiB. No data/control switching, payload inspection or request rewriting.
6. Any close/error/timeout closes both sides. No automatic data replay, half-close recovery or tunnel migration. HTTP receipt reconciliation handles uncertain mutation outcomes as before.

Pending/join/readiness timeout: 10 seconds. Control heartbeat at most 15 seconds, stale threshold 45 seconds. Relay outer reconnection is jittered/bounded on clients; retrying transport setup never retries a prompt by itself.

## Resource bounds

Initial fixed defaults: 4 pending and 16 active streams per route, 2 per bootstrap / 8 per device access, 256 total active streams and 64 control routes. Separate unauthenticated upgrade/IP and per-route open rate limits. Each direction has at most 8 queued 32-KiB messages plus bounded in-flight framing overhead; stalls close within 10 seconds. Check bounds **before** queueing or message assembly, not merely in callbacks after a full message allocation. Apply independent limits in relay, desktop connector and Android; malicious relay behavior must not bypass connector limits.

Node relay/connector pin `ws` 8.22.0 and use supported `maxPayload: 32768`, `maxFragments: 1`, bounded `maxBufferedChunks`, compression off and `autoPong: false` with bounded manual control replies. Emitters always send unfragmented FIN binary messages. Receiver enforcement can retain one bounded non-FIN fragment while its authorized connection remains alive; a following fragment is rejected by the public fragment limit. There is no separate incomplete-frame deadline: valid pongs can keep that bounded partial frame alive. Do not claim immediate first-header FIN rejection or patch private parser internals. Android's ordinary OkHttp WebSocket API is NOT sufficient for this claim: it assembles unbounded inbound fragmented messages before `onMessage`. Use a version-pinned bounded framing implementation/guard: Java-WebSocket draft pre-allocation frame cap, reject every fragmented data message before accumulation, explicit extension rejection, bounded upgrade header parsing, and preserved hardened `copyInstance`. Bound outgoing library queues and automatic pongs separately.

Android logical retirement is immediate, but physical HTTP/SSE cancellation and raw/TLS socket close run on a bounded per-transport IO owner, never the Main thread. The real outer TCP socket is registered before DNS/connect and wrapped with the ordinary TLS provider and relay hostname for WSS; cleanup aborts raw TCP before TLS so asynchronous WebSocket startup cannot escape ownership. Awaitable bootstrap cleanup bounds the caller's wait to 10 seconds and reports a safe failure on timeout; this is not a guarantee that every arbitrary native-provider hang can be forcibly terminated. No lifecycle monitor is held across physical cleanup.

Android JSON GETs may recover once from a pre-response closed-socket failure on an idle pooled connection: exact `SocketException`, or exact `IOException` with an immediate `EOFException` cause. At most two physical calls share one 15-second logical deadline; timeout/interruption and protocol exception categories are excluded. Recovery requires an active, uncanceled, non-retired transport; it uses the same retry-disabled client and a fresh connection after the failed one is discarded. HTTP responses, body/schema errors, TLS failures and all non-GET requests never enter this recovery path. This read-only recovery must not resend a mutation or replace original command-receipt reconciliation.

Retain inner API limits (64 KiB request, 32 KiB input text, 2 MiB snapshot). An SSE connection has no 2-MiB lifetime limit: complete large snapshots stream across bounded chunks. Close/stall releases local sockets, threads, buffers and transient stream-quota references, never deletes durable device grants or stops upstream DSH work. This is a fail-close transport for the existing HTTPS protocol, not a general-purpose transparent TCP tunnel; a dropped final response remains an uncertain command outcome. Host limits relay-mode active devices to 16 and outstanding live pairing offers to 16 transactionally, in addition to the 64-grant bound.

## Host integration and operations

Host SQLite is authoritative for relay grants bound to device IDs. Connector observes local-admin additions/revocations and sends full snapshots on connection/change (bounded periodic refresh covers a separate CLI process). Local device revoke also invalidates outer grants; inner host revocation remains authoritative even if relay is unavailable or malicious. `forget` stays local and does not imply remote revocation.

Host plugin owns the existing server plus optional connector lifecycle. Default remains direct mode. Connector target must match the loopback HTTPS listener; no new browser token or second DSH runtime. Local operator commands generate v2 invitations and show safe connection state/grants. Scrub transport failures/events; never log payloads, secrets, full invitations or private cert keys. Initial public deployment uses one isolated relay service and existing HTTPS routing, not a DSH port or public generic TCP proxy.

## Acceptance and non-goals

Require direct-mode regression tests, two-host/two-device authorization isolation, wrong pin/trust/name/date before credential bytes, malicious route/splice, replay/double-join/stale generation, oversize/fragment/compression/header bounds, stalls and queue caps, complete large snapshots/long SSE, revoke/restart cleanup, lost mutation response with one adapter dispatch, and native release foreground/reconnect/transport acceptance. Physical Wi-Fi→mobile and public relay acceptance are separate from local tests.

Not provided by this change: waking a powered-off PC, guaranteed access on every censored network, permanent Android background connection/push, accounts/billing/public anonymous host signup, device-grant secret recovery, automatic public deployment, or unverified cryptographic claims. One trusted pairing step is required; ordinary later access should be automatic inside DSH Mobile without a separate VPN app.
