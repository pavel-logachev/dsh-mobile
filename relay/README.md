# DSH Mobile opaque relay 0.2

Small operator-managed Node 24 service. It routes bounded WebSocket streams carrying the companion's **existing end-to-end TLS**. It has no DSH adapter, model execution, user account service, public provisioning API or application cryptography. The authoritative wire specification is [the transport contract](<../docs/RELAY_PROTOCOL.md>).

## Build and local administration

```powershell
npm.cmd ci --ignore-scripts
npm.cmd run check
node dist/cli.js init --state <private-directory>/relay.sqlite
node dist/cli.js provision --state <private-directory>/relay.sqlite --output <new-private-connector.json>
node dist/cli.js serve --state <private-directory>/relay.sqlite --bind 127.0.0.1 --port 8088
node dist/cli.js revoke-route --state <private-directory>/relay.sqlite --route <route-id>
```

Provisioning generates independent random `routeId` and `connectorToken`, stores only the token digest in SQLite, and writes the capability to an **exclusive** output file. It never prints the token. Transfer the file only over an owner-trusted channel. A failed/existing output cannot silently create an extra usable route. Route revocation is observed by a running service within 250 ms and closes its generation's sockets.

Files are created with POSIX modes 0700/0600. Windows does not enforce these POSIX permission bits: the owner must use a private directory with suitable ACLs. No credential, certificate key or database belongs in Git or diagnostics.

`serve` defaults to loopback. Native TLS uses `--cert <PEM> --key <PEM>` (TLS 1.2 minimum). Alternatively, an **independently configured, trusted HTTPS ingress** may use `--external-tls`; that flag is an explicit operator acknowledgement, not TLS implementation. Non-loopback plaintext without either choice is refused. The HTTP backend must never be published directly. The service does not install HTTPS routing, touch firewalls, deploy itself, or expose a DSH port.

Optional `--prefix /canonical/path` is applied to all endpoints, including `GET /canonical/path/healthz`. Health returns only `{"ok":true}`. Keep path prefixes identical in invitations/connector setup and ingress routing. Offloaded TLS must preserve Upgrade and authorization headers and provide its own public-edge quotas; forwarded IP headers are deliberately not trusted by this service. Backend IP quotas consequently share the ingress peer address.

## Programmatic seam

```ts
import { RelayState, createRelayServer } from './src/index.ts';
const state = new RelayState(':memory:'); // Isolated tests only.
const owner = state.provisionRoute(); // {routeId, connectorToken}; do not log it.
const relay = createRelayServer({ state, port: 0 });
const { baseUrl } = await relay.start(); // Loopback ws:// fixture URL.
// A connector and mobile client now use the documented header/wire protocol.
await relay.close();
state.close(); // Injected state remains caller-owned.
```

Production passes an absolute `statePath` or caller-owned `state`. The exported `startRelayServer(options)` also starts the listener. `stats()` returns non-sensitive transient counts `{controls,pending,active,sockets}` for acceptance; it is not a public endpoint. `limits` and `timings` test seams can **lower**, never raise, fixed defaults.

## Bounds and lifecycle

- Route ownership alone is durable (maximum 256 provisioned routes); grant snapshots and tunnels are ephemeral. Each control connection has a fresh generation. Second live controls are rejected. Control loss clears grants and pending/active streams; reconnection requires a fresh acknowledged authoritative snapshot.
- Full snapshots: 64 grants/32 KiB; strict UTF-8, exact required properties, canonical IDs/digests, finite expiry, bootstrap quota 2 and device quota 8. Snapshot replacement closes removed/changed grants before ACK. Expiry has a deadline timer and new opens always recheck it.
- Default 64 live controls, 4 pending/16 total reserved-active streams per route, 256 total reserved-active. Per-access limits count pending sockets too. Joins are random, single-use, bound to route/generation/stream/mobile socket. No destination is accepted from a peer.
- `ws` is pinned to 8.22.0. Public parser options are `maxPayload:32768`, `maxFragments:1`, `maxBufferedChunks:8`, compression off and automatic pong off. Every outbound application message is FIN binary. No private parser patch is used. A lone inbound non-FIN fragment may be held by `ws` under its 32-KiB cap until timeout or another fragment; no application payload is delivered, and any continuation exceeds the one-fragment bound. This is bounded rejection rather than immediate header-level FIN rejection.
- Each outbound direction has at most eight messages/256 KiB plus parser/current TCP chunk, framing and OS buffers. Source reads pause after two pending sends; send callbacks resume them. Further admission beyond a bound fails closed. Send stalls close within 10 seconds plus the sweep interval (maximum 250 ms).
- Upgrade headers: 8 KiB/32 headers. Separate bounded IP/global upgrade, route-open, control-update, application-message and tiny-message limits. Zero-length DATA is rejected. Ping/pong input is limited separately; automatic pong is disabled and only one manual pong is pending per peer.
- Pending/readiness: 10 seconds. Ping: 15 seconds. Missing pong: 45 seconds plus sweep scheduling. Shutdown, revocation, expiry, overflow and errors release transient resources. Durable grants are owned by the host, not deleted by stream closure.

This transport deliberately closes both directions on a socket close/error. It is not a general-purpose TCP/half-close proxy and does not migrate/replay bytes. An interrupted final HTTP response retains the existing uncertain receipt behavior. It does not promise availability on all filtered/captive networks, Android background persistence, waking a stopped PC, or secret recovery.

## Local verification

`npm.cmd run check` compiles and runs public-boundary tests. The suite covers route capability isolation, real relay control/data rendezvous, cross-route/double joins, revocation/change/expiry, fresh generations, quotas, large opaque streams above 2 MiB, parser/header/text/compression rejection, ping/zero-message floods, rate limits, slow nonreading receiver reset, readiness/heartbeat, private provisioning and external local revocation.

These tests use loopback synthetic peers. They do not prove host TLS pinning, actual DSH command delivery, Android release behavior, physical Wi-Fi/cellular roaming or public TLS ingress readiness. Those are separate integration/acceptance gates. Public deployment and production settings are outside this package's automatic actions.
