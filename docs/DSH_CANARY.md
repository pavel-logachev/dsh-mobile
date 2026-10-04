# Isolated real-DSH canary

This canary executes the **actual DSH SessionController from an exact verified runtime: 0.2.0-rc.2 or 0.2.1-alpha.1** and the companion's in-process adapter, with a deterministic implementation of the official `LlmAdapter` interface. It is not a replacement SessionController, RPC stub, or fixture host. Its model output is synthetic; it does not prove an external model, the user's running DSH profile, a production plugin mount, or physical-phone network acceptance.

## Run

From the repository root, with Node 24 and the host dependencies/build supplied:

```powershell
node --test tools/dsh-canary/*.test.mjs
node tools/dsh-canary/typecheck.mjs
node tools/dsh-canary/run.mjs
```

Full mode requires `host/dist/dsh-adapter.js` from the coordinated host build. The canary does not build or modify host-owned files. `--controller-only` proves the official controller lifecycle and cold reads without importing the companion adapter; its receipt explicitly says the mobile adapter was not run.

The installation output anchor is `$HOME/Documents/DeepSeekHarness`, resolved before environment isolation. The default runtime is its `runtime` child. An explicit **read-only runtime source** can be supplied, including an inspected alpha build staged under the installation cache:

```powershell
node tools/dsh-canary/run.mjs --runtime-root '<installation>/runtime'
node tools/dsh-canary/typecheck.mjs '<installation>/runtime'
```

The only output root is `<installation>/cache/dsh-mobile/canary`, **independent of `--runtime-root`**. Selecting `<installation>/cache/<staged-alpha>/runtime` does not create nested `cache/cache/...`; a normal canonical `--cache-root '<installation>/cache/dsh-mobile/canary'` remains valid. `--cache-root` may name that exact root, not an arbitrary directory or a sibling of an alternate runtime. A unique `run-*` child owns the synthetic home, JSONL sessions, storage, attachments, workspace and temporary files. All versions, including alpha runs, write receipts at `<installation>/cache/dsh-mobile/canary/run-<unique>/receipt.json`; use the actual `receiptPath` returned by each run, not a guessed historical staging path. Acceptance is bounded by `--timeout-ms` (default 45000, maximum 120000). Successful cleanup leaves only the synthetic receipt.

### Real registry-mode profile insertion

After building the host, run this **separate** automated acceptance mode:

```powershell
node --expose-internals tools/dsh-canary/run.mjs --registry-check --port 19446
```

It adds the actual installed Cordis Loader/Include, WorkspaceController, AgentPresetRegistry and one explicitly synthetic empty preset to the audited service graph. It writes a new owned profile patch under the overridden synthetic `DSH_HOME`, parses it with the installed `dsh-app-boot` patch reader (never calling `boot`/`loadProfile`), then mounts the companion as the last `insert` row via its built `file://.../host/dist/plugin.js` URL and private `configPath`. It never reads or modifies the owner's profile. Normal mode still forbids Loader and still has no preset registry.

The canary asserts that the companion and registry share a realm, the required services are active, `ctx.get('workspaceRegistry')` succeeds without companion inject, and repeated reads have different proxy references but the same `cordis.original` identity. It seeds three real registered workspaces and one real session per workspace, pairs a synthetic all-scope device, and checks authenticated HTTP workspace order/titles/UUIDs, per-workspace session lists, create/attachment in the real WorkspaceController baseline, requestId dedup, rename/reorder/archive, explicit registry lookup loss returning 503, and plugin/listener disposal. The lookup-loss fault injection uses the provider-owned Cordis `set` API; disposing the real registry provider instead also unloads the required SessionController/companion and closes the listener. There are zero model calls in this mode. It does not exercise a relay or a physical phone.

`--expose-internals` is required **only for this mode**: the installed Loader's module loader otherwise imports a native fallback DLL into TEMP, which Windows pins until process exit and prevents in-process cleanup. The runner rejects a missing flag before creating state. This flag affects only the new canary process, not installed runtime files. Do not combine `--registry-check` with `--serve` or `--controller-only`.

## Why direct composition

The installed `sdk-minimal` template includes an external model adapter and shell/tool plugins. The normal base/profile graph also has live credential/account, configuration and telemetry services. A template name therefore cannot establish a credential-free canary.

The harness imports the installed official package exports and composes a new Cordis Context directly. It does not start the CLI, copy a profile, load a home patch, start a replacement DSH GUI, or inject into the user's running process. Installed DSH manifests are checked against the exact allowlist `0.2.0-rc.2`, `0.2.1-alpha.1`, and every DSH component must match the selected verified runtime; entry SHA-256 hashes are written to the receipt. Cordis and timer packages retain their independently versioned versions.

Actual services include the LLM registry, Agent registry/factory/loop, default-model selection, Session store, JSONL persistence, query/projection services, workspace registry, JSON/domain storage, local FS/attachments, Typert registry, commands, client connection/file uploads, system-prompt service, empty native ToolRuntime and SessionController. The optional preset registry is absent: the companion reports an empty preset catalog, not a fabricated preset. No tools are advertised or executable.

File uploads require the real client connection service, whose BrowserAuth initializes a signing record. Only a **new, ephemeral in-memory** official `CredentialProvider` extension is supplied. It permits the one `client-connection/browser-session` record and rejects credential-reference resolution and other writes. The local credential provider, `.credentials.yaml`, `.env`, account/subscription providers and inherited model credentials are never loaded. The synthetic signing value is not persisted or printed.

## Checked scenarios

1. Real `create`, adopting the same explicit session identity; no LLM call during creation.
2. Queue prompt with an actual UUID requestId; durable original requestId preserved; repeating the same requestId causes no second call.
3. Real live `follow`: both provisional assistant chunks and durable final events.
4. Observer disconnect while a controlled model turn is running; disconnect does not cancel it.
5. Reconnect receives an authoritative replacement snapshot containing the completed turn and original requestId.
6. Explicit real controller cancellation reaches the custom adapter's AbortSignal and settles to idle.
7. Actual JSONL durability barrier, disposal, then a new Context over the isolated persisted state.
8. Cold `list`, `inspect`, `page`, `projections` and one opening `follow` snapshot leave both Session and Agent unattached, with zero LLM calls.
9. Built companion adapter: absent-preset/session catalogs, cold-safe snapshot/watch, create/adopt, prompt, normalized provisional text, disconnect/reconnect and conservative observed-cursor cancellation.

The cold-read boundary is essential: rc.2 `follow` promotes a prepared persisted session **after the opening snapshot is yielded**. Cold reads call `next()` once, then abort/`return()`, never a second pull. The companion watcher polls cold-safe authoritative openings rather than accidentally resuming a session.

Cancellation is session-active stop at admission. The companion's expected-cursor guard is conservative, not an atomic upstream run-ID cancellation primitive.

### Exact synthetic text

| Scenario | Prompt | Successful result |
| --- | --- | --- |
| Live | `DSH_MOBILE_CANARY_LIVE: synthetic prompt; return CANARY_OK.` | `CANARY_OK — deterministic official LLM adapter; no external model.` |
| Disconnected observation | `DSH_MOBILE_CANARY_RECONNECT: synthetic prompt; complete while observer is disconnected.` | `CANARY_RECONNECTED — completed while observation was disconnected.` |
| Cancellation | `DSH_MOBILE_CANARY_CANCEL: synthetic prompt; wait until explicitly cancelled.` | Partial `CANARY_CANCEL_PARTIA`, aborted/idle |
| Companion | `DSH_MOBILE_CANARY_MOBILE: synthetic prompt through the real companion adapter.` | `CANARY_MOBILE_OK — normalized from the real DSH SessionController.` |
| Companion cancellation | `DSH_MOBILE_CANARY_MOBILE_CANCEL: synthetic companion cancellation test.` | Partial `CANARY_MOBILE_CANCEL`, aborted/idle |

## Isolation and cleanup

- `DSH_HOME`, HOME/USERPROFILE and TEMP/TMP are replaced before official service imports/composition; only a small Windows boot environment is retained.
- A process-local guard blocks fetch, HTTP(S), sockets, TLS, HTTP2, UDP, DNS and subprocess launch. Ordinary mode cannot listen. Registry-check alone permits HTTP and sockets to **127.0.0.1 at its exact declared port, only while its own listener is active**. Live ports 3080/3081/19445 are always forbidden, as are other loopback ports, hostnames, external destinations, HTTPS and relay connections. FS write/open/mutation wrappers reject targets outside the owned run.
- The only model provider is `dsh-mobile-canary`, model `deterministic-text-v1`. Each request asserts this route and zero tools. The stream emits valid block/text/usage/terminal chunks and nothing after finish; controlled gates make cancellation/disconnection deterministic.
- Assertions reject settings/configuration/live profile loader, account/telemetry, web server and process/tool services in the new Context. The optional registry-check phase permits only the audited Loader/Include graph above, rooted in its owned synthetic profile.
- Cleanup cancels/releases controlled work, disposes the entire Cordis graph, leaves the Windows-pinned current workspace before removing it, and deletes synthetic home/workspace/temp. There are no canary-owned services or firewall changes.
- No tracked file contains installation-specific absolute home paths, credentials or private conversation text. Receipts stay in the authorized cache.

These guards are **defense in depth, not an OS security sandbox**: native modules and already-captured APIs can bypass JavaScript wrappers. The explicit audited service allowlist, isolated paths and absent external adapters/tools are the governing safety boundary. This task does not assert cryptographic proof that the installation cannot be malicious. Forced process termination cannot guarantee effect cleanup; inspect its isolated run after an interruption. The bounded acceptance timeout rejects the test and attempts lifecycle cleanup; it is not a license to mutate the user's DSH installation.

## Optional bounded loopback host

Only after the full canary passes, the same process may expose the real companion host for a debug emulator flow:

```powershell
node tools/dsh-canary/run.mjs --serve --port 19443 --serve-ms 600000
```

The delegating operator should own/manage a long-lived background job. The canary does not spawn one. `--serve` requires an explicit unreserved port (live DSH/companion ports 3080/3081/19445 are rejected), binds **127.0.0.1 only**, and has a maximum lifetime of 30 minutes. It never permits an outbound connection. Node internally calls `dns.lookup` even for a numeric listen address; the guard resolves exactly `127.0.0.1` in memory, with no OS resolver. No production network/TLS acceptance is implied by debug HTTP.

A one-use pairing offer with read/execute grants for only `canary` is written to the private cache invitation as `{version:1,baseUrl,pairingToken}` (no pin for loopback HTTP). The token is never printed. The stderr `CANARY_READY` record contains only baseUrl, invitation/ready paths and lifetime. By default the invitation lives in the owned `run-*` child. Optional `--invitation-file '<installation>/cache/dsh-mobile/canary/<unique-name>.json'` is permitted only within that cache, with an existing nonredirected parent and exclusive creation; existing files are not overwritten or removed on failed creation.

The nonsensitive ready metadata includes `mode: isolated-dsh-canary`, workspace/host names, existingSessionId/existingMessage, createdSessionTitle, exact prompt/expectedAssistantText, expiresAt and closesAt. It may be consumed by a debug Android acceptance runner; do not display or copy the invitation token into logs or tracked test configuration.

Exactly three synthetic strings are admitted by the serve carrier before reaching DSH: the two cancellation prompts above and:

```text
DSH_MOBILE_CANARY_ANDROID: synthetic emulator prompt.
```

The Android response is:

```text
CANARY_SERVE_OK — deterministic real DSH; no external model.
```

Unknown/private prompts are rejected, not persisted. The server's receipt records only these synthetic serve requestIds/sessionIds, prompt text and outcomes; prelude calls are counted separately. A successful serve close removes its invitation/ready files, closes the host/adapter and disposes/removes the DSH state. Physical phone operation, real TLS/pins and production profile mounting remain separate gates.

## Receipt and verification

The final stdout is a compact JSON summary with checks/counters/cleanup and `receiptPath`. The full cache receipt contains:

- `kind`, exact installed DSH version, Node version, timestamps and `success`;
- per-phase checks plus actual normalized controller/companion message results;
- installed package versions and loaded entry hashes;
- controller/remounted adapter call counts;
- prohibited `networkAttempts`, `subprocessAttempts`, `outsideWrites`, total `listeners`, and remaining `activeListeners` (zero after cleanup);
- lifecycle/state cleanup results, or the failing phase/error/stack;
- optional serve-only synthetic request results.

Related companion regression verification (from the repository root; substitute the installation path, no live activation):

```powershell
$env:DSH_MOBILE_CORDIS_MODULE = Join-Path '<installation>/runtime' 'node_modules/@deepseek-ai/cordis/lib/index.js'
Push-Location host
npm.cmd run check
npx.cmd tsc --noEmit -p tsconfig.json
Pop-Location
```

Record current test/check counts, package versions and actual canary counters in your ignored local receipt. Require zero prohibited network/subprocess/outside-write attempts, listener disposal and owned-state removal. The direct adapter canary supplies `expectedWorkspaceId` to create/prompt/cancel; the serve wrapper forwards it unchanged.

An isolated synthetic-model compatibility result is not production readiness or combined native UI/real-DSH end-to-end acceptance unless that distinct flow is separately executed and checked.
