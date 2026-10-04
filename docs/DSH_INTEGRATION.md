# DSH in-process integration — development preview

## Compatibility and evidence

The adapter contract was inspected against **DSH 0.2.0-rc.2** and the installed **@deepseek-ai/cordis 4.0.4**. It is not a generic DSH HTTP client. The plugin receives the existing `ctx.sessionController` and `ctx.agentPresets`; provider accounts, agent execution, tools and authoritative history remain in that same DSH process. No browser launch token, cookie or provider credential is read.

Relevant installed package sources/declarations (generic relative locations):

- `node_modules/@deepseek-ai/dsh-api-session-controller/lib/types/index.d.ts` and `types.d.ts`: direct list/create/prompt/cancel/follow/projections/page requests and responses.
- `dsh-api-session-controller/lib/index.js`, creation at 686–710, identity adoption at 253–274, prompt admission at 850–899 and requestId reconciliation at 1063–1073.
- `dsh-api-session-controller/lib/index.js`, follow opening at 1445–1564 and history paging at 1652–1695.
- `dsh-agent-preset-registry/lib/types/types.d.ts` and `index.d.ts`: roster `{presets:[{id,name?,isDefault,broken?}]}`. Only usable ID/name entries are projected. Composition YAML from `readDocument` and preset switching via `select` are **not** exposed.
- `dsh-session/lib/types/types.d.ts` and `surface.js`, `dsh-llm/lib/types/message.d.ts`, `types.d.ts` and `assistant-stream.d.ts`: message envelopes, replacement positions, compact streams and text block semantics.
- `dsh-user-questions/lib/types/types.d.ts` and `dsh-user-approval/lib/types/types.d.ts`: pending question projection and approval audit events.
- `cordis/lib/types/registry.d.ts`, `fiber.d.ts` and `lib/index.js`: object plugins, required dependency injection, asynchronous effect disposal.

The plugin requires an explicit `dshVersion: '0.2.0-rc.2'`. This is an **operator-declared version**, not automatic detection. Other declared versions fail closed. Method presence alone cannot establish semantic compatibility; inspect and test a new runtime before changing this gate.

## Files and public seams

- [dsh-adapter.ts](../host/src/dsh-adapter.ts): `createDshAdapter({dshVersion,sessionController,agentPresets?,workspaces,events?,pollIntervalMs?,throttleMs?})`; implements [HostAdapter](../host/src/types.ts). An absent preset registry gives an empty roster/host default only; selecting an explicit preset is rejected.
- [plugin.ts](../host/src/plugin.ts): `{name,inject,apply}` module/default export; production dependency injection is `['sessionController','agentPresets']`. Inspected Cordis does **not** support optional dependency syntax: `{required:false}` would be intercept configuration, not an optional service. Do not put this plugin inside a per-agent preset; it is a host-level companion.
- [fixture-adapter.ts](../host/src/fixture-adapter.ts): synthetic in-memory fixture, `upstreamVersion: 'fixture'`, no DSH/model/network dependency.
- [demo.ts](../host/src/demo.ts): explicit standalone loopback fixture composition; importing it starts nothing.

The plugin's `apply` returns `Promise<disposer>` directly. Cordis awaits startup and the eventual disposer even when removal races startup. Removal aborts mobile observation, closes the companion listener and releases its local state; it never calls DSH cancel or disposes DSH controllers. Errors use fixed path/token-free [HostError](../host/src/errors.ts) messages.

## Observation is cold-safe

In rc.2, `follow` yields its opening snapshot **before** promoting a prepared cold session to an Agent. A second iterator pull runs that promotion. Continuing a raw follow solely for a mobile read could therefore activate execution.

The adapter reads **only the first frame**, aborts the read-owned signal, and calls `iterator.return()` in `finally` for every snapshot. This applies to all sessions, not just those that previously appeared cold, because a live summary may race Agent disposal.

Live watch uses session-scoped Cordis `session/event`, `agent/assistant-stream` and `api-session/status` listeners, plus cold-safe first-frame refreshes (default 1 s). Published full replacements are throttled (default 100 ms). Factory compositions without an event bridge use the same polling snapshots. Reconnect discards prior process-local state and opens a new authoritative view; no cursor replay or automatic mutation retry is promised. Persistent gaps cannot spin unrestricted immediate refreshes. Abort removes subscriptions and timers; observer disconnect does not cancel a task.

## Projection and scope

Workspaces are explicit owner-configured `{id,name,path}` entries. Existing absolute directories are canonicalized with `realpath`; Windows path comparison is case-insensitive. A session must have an exactly matching canonical `cwd`. Unknown roots, missing paths and unconfigured subdirectories are denied; subagent-origin sessions are excluded. Ordinary fork lineage alone does not turn a session into a subagent. Clients submit workspace IDs, never arbitrary paths. Per-device read/execute grants are additionally enforced by the companion server.

**This is owner-authorized transcript filtering, not a filesystem sandbox.** A trusted DSH agent may have broader tool permissions, and visible user/assistant text may contain cross-workspace material. Those authorized text messages are not scrubbed for arbitrary secrets or paths. Structured host cwd, raw tool arguments/results, system/developer instructions, reasoning, provider replay metadata and unknown event JSON are never dumped into mobile messages.

Durable `user/message` reads a direct `UserMessage`; `assistant/message` reads `data.message`. Only text blocks are presented. `source.kind:'user'` + `rpcId` becomes the correlated requestId. Surface replacements use **current surface positions**, not numerical sequence ranges. Known complete ranges remove shadowed text; durable assistant settlement replaces provisional text. Assistant chunks include only visible text-delta/text block-end, never reasoning/tool chunks. Block-end replaces accumulated text rather than appending it twice.

History is limited to 100 visible messages. A bounded raw page can omit a surface replacement endpoint. The adapter then returns `activity:'unknown'`, a desktop notice and **no ambiguous conversation text**, instead of presenting obsolete answers as final. Reading those compacted histories on mobile requires a later paging/provenance implementation. Unknown required events also fail closed; ignorable extensions stay opaque. Gaps trigger bounded resync. Pending questions/approvals show `waiting` and a desktop notice; mobile answers/approvals are unsupported.

## Mutations and delivery

`create` uses only the canonical configured cwd, a validated preset ID when supplied and `session-${requestUUID}`. rc.2 supports explicit identity adoption and verifies cwd/preset conflicts. The original requestId is forwarded to `prompt` with `mode:'queue'` and one text block. Prompt admission checks the inbox/log for `source.rpcId`; this is useful reconciliation, **not an exactly-once promise**. The companion's persistent command ledger owns duplicate admission, crash/uncertain outcomes and conflict handling. It must never blindly retry uncertain work.

Cancellation requires the caller's `expectedCursor`. The adapter rereads the authoritative opening, checks running and exact cursor equality, then calls the synchronous `cancel({sessionId})` without another await. A stale view is rejected. Accepted means **requested**, not already stopped. rc.2 has no atomic target-turn/run compare; cancellation still concerns the session's active work at admission and the conservative freshness guard is not a historical-run cancellation guarantee.

## Manual installation only

No installed runtime/profile is modified by this project, and the plugin is not auto-enabled.

1. Use supported Node 24 and build the host package locally (`npm.cmd ci`, `npm.cmd run check` on Windows; `npm ci`, `npm run check` elsewhere).
2. Arrange for the built local `dsh-mobile-host/plugin` module to resolve from an **isolated, operator-owned DSH composition**. A local package installation/module path is an operator action; no public publication or automatic production install is assumed.
3. Make a private copy of the generic [Cordis patch example](../examples/cordis.patch.yml). The committed example is disabled and contains deliberately non-working placeholders. Replace all paths, verify runtime version, choose explicit workspace roots, then enable it only in the isolated canary after review. The `insert`/`id`/`name`/`config` shape is based on inspected loader declarations and DSH's shipped patch. Do not copy it to a running profile automatically.
4. Configure TLS and either a direct reachable HTTPS route (invitation v1) or the optional built-in opaque WSS relay (invitation v2) before real-phone operation. Your relay deployment/public/native acceptance requires independent review and rollback gates. Remote mode keeps companion HTTPS on a fixed numeric-loopback target and needs no external phone VPN/Tailscale, home port forward or public DSH endpoint. [Remote setup](<REMOTE_SETUP.md>) prepares owner-private TLS/connector state and a disabled additive snippet without activating/restarting DSH; [deployment](<RELAY_DEPLOYMENT.md>) is separate. Keep keys, connector capability, state and invitations outside Git with private OS ACLs. Direct non-loopback/plain production HTTP remains rejected.
5. Pair/revoke through the companion's documented local admin tools. Mobile invitations contain companion credentials, never DSH credentials. Installation/start command flags for a particular DSH profile must be validated separately; this document intentionally invents **no dsh CLI flags**.

## Explicit local fixture demo

The executable identifies itself as **DSH Mobile demo — not connected to DSH** and binds exact `127.0.0.1` with debug-only HTTP. Its SQLite database stores device grants/receipts, while fixture conversations are in memory and reset after restart. It does not execute tools or call a remote model.

```powershell
# From host/, after building; both chosen locations must be private and ignored.
node dist/demo.js --state-dir <PRIVATE_DEMO_DIRECTORY> --port 0 --invitation-file <NEW_PRIVATE_INVITATION_JSON> --answer-delay-ms 2000
```

Port 0 chooses an ephemeral local port. The one-use invitation is created intentionally on explicit startup. With `--invitation-file`, stdout prints the location only and refuses overwriting an existing file; without it, a warning precedes intentional local invitation JSON output. Never pipe that output to durable logs or commit the invitation. Windows permissions inherit from the chosen private directory; Unix file mode alone is not a Windows ACL guarantee. Ctrl+C/SIGTERM disposes fixture timers and closes the listener. For programmatic managed tests, `runDemo({stateDirectory,port?,answerDelayMs?})` returns `{invitation,host,adapter,close}` and does not print secrets.

Stable synthetic test literals from `FIXTURE`:

| Field | Value |
| --- | --- |
| workspaceId / workspaceName | `demo` / `Demo workspace` |
| existingSessionId / existingSessionTitle | `demo-session` / `Synthetic example` |
| existingMessage | `This is a synthetic conversation. No model or DSH runtime is connected.` |
| createdSessionTitle | `Demo conversation` |
| expectedAssistantText | `Synthetic demo answer. No model was called.` |

Default answer delay is 2000 ms; allowed configured delay is 0–60000 ms. Watch lifetime does not own the delayed answer. A duplicate identical fixture prompt returns the prior admission without a second answer; different text under the same identity is rejected. These are fixture capabilities, not proof of live DSH persistence or production readiness.

## Checks and remaining gates

[dsh-adapter.test.ts](../host/test/dsh-adapter.test.ts) exercises the public adapter seam with literal synthetic [rc.2 fixtures](../host/test/fixtures/rc2.ts): replacements, provisional/durable settlement, private-field omission, cold-read cleanup/no second pull, roots/subagents, creation/queue prompt, unsupported events, gaps, 100-message bounds, empty cursor -1, waiting notices, cancellation freshness, polling reconnect, declared-version gate, fixture and demo composition.

The optional genuine Cordis lifecycle test uses `DSH_MOBILE_CORDIS_MODULE` supplied **outside tracked files** as the absolute installed module entry. It creates a fresh isolated Context with harmless provided controller stubs; it verifies authenticated loopback startup and disposal, including removal racing async startup. It makes no DSH model/session calls. Without that environment variable the composition check is explicitly skipped, not silently counted as acceptance.

Run `npm run check` for the current tree and record actual counts locally. Fixture/core/Cordis composition checks, real SessionController canary, native emulator, physical-phone route and production-profile installation are distinct acceptance gates; do not infer one from another.
