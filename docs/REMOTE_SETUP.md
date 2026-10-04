# Owner-local remote setup

For normal LAN or Tailscale installation, start with the [recipient agent runbook](SETUP.md). The relay described here is optional and self-hosted: there is no shared service or tracked public deployment.

This procedure prepares the desktop companion for the [relay protocol](RELAY_PROTOCOL.md). It does **not** activate the installed DSH profile, deploy a relay, start a server, restart DSH, change firewall/VPN/trust, or build/sign an APK. Public relay and physical-phone acceptance remain separate gates. The PC must remain powered on and DSH running.

## Private inputs and preparation

Use Windows PowerShell 5.1, installed Node 24 and a vetted installed OpenSSL 3. Follow [the prerequisite download/consent procedure](SETUP.md#prerequisite-download-and-installation--separate-consent) for official Node/Git sources, independent new-shell verification and protecting DSH's own Node launcher. The [setup helper](../tools/mobile-remote-setup.ps1) discovers OpenSSL from PATH or installed Git/OpenSSL directories; it installs nothing. Explicit `-OpenSslPath` is supported (use it for Git's `ucrt64/bin` executable if it is not auto-discovered).

The relay operator must provision a **private file** containing exactly `{routeId,connectorToken}`: a 32-lowercase-hex route ID and an independent canonical 32-byte base64url connector credential. Transfer the file through an approved private route; never paste either credential into command arguments, chat, shell logs or the active YAML. The helper reads it internally, validates its own and its parent directory's protected owner-only Windows ACL, and refuses weak permissions rather than repairing them silently. Do not use a Git directory, shared downloads folder, redirected/junction path, UNC path or alternate data stream for private inputs.

**Registry mode is the default** for all registered DSH projects, including future projects: the helper prepares `"workspaceSource":"dsh-registry"` with an empty list when no subset file is supplied. It never guesses a project directory. Read and execute grants are separate approvals; neither follows from preparation. A deliberately supplied private `-WorkspacesPath` JSON containing `{id,name,path}` selects the advanced explicit-list subset instead. IDs/paths must be unique existing absolute local directories with no reparse ancestor. A nonempty explicit list is mutually exclusive with registry mode.

Review the chosen scope before initialization. The live Cordis registry supplies DSH workspace UUIDs, sanitized titles and durable sidebar order without plugin reload when projects are added, renamed, reordered or deleted. At most the first 100 registrations are considered; missing directories are omitted entirely and registry-archived sessions are hidden. This is not filesystem discovery. Workspace filtering still does not sandbox DSH tools or redact arbitrary transcript text.

From the project root (the relay URL below is supplied by the operator, not a tracked deployment address):

```powershell
$private = Join-Path $env:LOCALAPPDATA 'DSHMobile/host'
$routeFile = '<ABSOLUTE_OWNER_PRIVATE_ROUTE_JSON>'
$relayUrl = '<APPROVED_WSS_ORIGIN_AND_PATH_PREFIX>'
# First verify the installed dsh --version; declare only an allowlisted result.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/mobile-remote-setup.ps1 `
  -Initialize -RouteCredentialsPath $routeFile -RelayUrl $relayUrl -DshVersion '0.2.1-alpha.1'
# For a deliberate restricted legacy subset only, additionally use
# -WorkspacesPath '<ABSOLUTE_PRIVATE_WORKSPACES_JSON>'.
```

The process-scoped execution-policy option does not change system policy. Inspect the printed **selected workspace IDs/names/paths** before activation. No connector credential or private key is printed.

Prepared durable state is `%LOCALAPPDATA%/DSHMobile/host`, outside Git/cache:

- `tls-cert.pem`, `tls-key.pem`: stable, independent inner HTTPS identity. OpenSSL generates RSA3072/SHA256, exact `DNS:h-<routeId>.dsh.invalid` SAN, CA:false, TLS server usage, 825-day validity. The unattended Node listener requires an unencrypted PEM private key; protected owner-only directory/file ACLs secure it at rest. This is not an APK signing key. No OS certificate root is installed.
- `host.json`: private config, including route capability, TLS paths, exact logical public HTTPS URL, numeric loopback bind, fixed port **19445**, and selected workspaces. The hostname does not require DNS resolution: it is the inner TLS authority over the opaque WSS tunnel.
- `identity.json`: pinned certificate/SPKI, config/snippet hashes and creation metadata. Verification checks the private key match, exact SAN, valid dates, serverAuth purpose, self-signature and stable certificate/pin.
- `cordis.additive.patch.yml`: disabled, separate insertion; only absolute plugin module path plus `{dshVersion,configPath}`. It contains **no inline connector credential**.
- `UNDO.md`, `setup-receipt.json`: additive activation/undo and nonsensitive preparation evidence. `state/` and `invitations/` are owner-only runtime directories. Setup creates no SQLite state, pairing offer or network connection.

Rerun the same command without `-Initialize` to verify the same inputs/scope/identity. `-VerifyOnly` is an explicit equivalent verification mode:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/mobile-remote-setup.ps1 `
  -VerifyOnly -RouteCredentialsPath $routeFile -RelayUrl $relayUrl -DshVersion '0.2.1-alpha.1'
```

Supply the same workspace file/host name when originally customized. Verification is byte-stable and makes no configuration writes. Missing/partial/unexpected/reparse/weak-ACL state or changed route credential, host name, workspaces, certificate, key or config fails closed. Do not delete files and regenerate to bypass a failure. Changes/rotation/expiry require an explicit reviewed migration, not automatic overwrite. Previously prepared explicit-mode identities/receipts are preserved: this newer helper changes candidate metadata/defaults and will refuse a mismatching historical receipt. Do not rerun initialization to bypass it. Preserve the original preparation receipt and record any deliberate registry-mode migration separately. Back up the complete private directory securely; keep the TLS key and connector capability private, independently of any APK keystore.

## Reviewed additive activation and undo

The parent/operator performs this separately, only after host/relay reviews and acceptance:

1. Build/check the host package and confirm the strict plugin `{dshVersion:<exact verified installed version>,configPath:<absolute>}` API. Verify the actual installed DSH version and that loopback port 19445 is free. Preparation alone does not establish these facts.
2. Privately back up the existing web profile patch (normally `$env:DSH_HOME/profiles/web/cordis.patch.yml`). Review the generated snippet and safe workspace scope.
3. Enable **only** the new `dsh-mobile-companion` insertion from the separate snippet, retaining every unrelated patch/plugin. Its `name` points to this project's built `host/dist/plugin.js`; config references private `host.json`. Do not place connector credentials or inline host config in the active YAML.
4. Confirm live plugin insertion and connector readiness through the existing runtime. Do not start a second DSH server or restart the user's running work without separate consent.

### Existing-device all-project migration (operator only)

Do not execute these steps as development side effects. After owner/architect review:

1. In the project’s host directory run `npm.cmd run build` (Node 24), then the full host check. The plugin gate is the exact allowlist `0.2.0-rc.2` / `0.2.1-alpha.1`; declarations, compiled JS and isolated normal/registry canaries were checked for both (Cordis 4.0.4 / 4.0.5-alpha.1). Verify the actual owner's installed version after any runtime upgrade; a declaration is not auto-detection.
2. Privately back up the current host configuration, complete private state (using a SQLite-safe backup while active, or copying only after companion disposal), and the additive profile patch. Preserve credentials/TLS/relay fields unchanged.
3. Temporarily remove/unmount **only** the additive `dsh-mobile-companion` insertion through the existing profile/manager workflow, retaining every unrelated row, and wait for companion listener/connector disposal. Never infer active HMR merely from package installation. The inspected manager serializes reconciliation through `hmr.runExclusive`; when HMR applies it the result is `applied`, otherwise `restart-required`. Require evidence of actual unmount before changing private configuration or mounting another build. Do not continue on `restart-required` without an owner-approved restart; do not stop ongoing DSH work as a development side effect.
4. Edit only the private host configuration: add `"workspaceSource":"dsh-registry"`; remove the old `workspaces` array or replace it with `[]`. Keep state path, host name, bind/port, TLS identity and relay capabilities unchanged. The plugin reads this JSON on apply, not continuously: editing the file alone is **not** a reload.
5. List devices and replace the intended existing active device's grants; no re-pairing or phone credential replacement is required:

   ```powershell
   $config = Join-Path $private 'host.json'
   node host/dist/cli.js devices --config $config
   node host/dist/cli.js grant --config $config --device '<EXISTING_DEVICE_UUID>' --read all --execute all
   ```

   Verify the nonsensitive output contains that device ID and both `['*']` grants. Unknown/revoked devices fail. Registry-mode CLI never reads the DSH profile or registry storage; it accepts only `--read all` and optional `--execute all`. Explicit IDs produce a clear error and require explicit-list mode.
6. After the companion insertion is fully unmounted, choose a **new, nonexistent** release directory (for example `host/releases/registry-proxy-fix-1`; use another suffix if it exists), copy the **entire** `host/dist` directory into it, then reinsert the companion with a new `file://` URL to that directory's `plugin.js` and the same `{dshVersion:<exact verified installed version>,configPath}`. For managed portable builds use [install-host.ps1](../tools/install-host.ps1) with `-Upgrade -Activate` rather than hand-copying modules. Do not overwrite the live-mounted release or copy just `plugin.js`: unchanged sibling URLs may reuse stale Node modules. No query-string cache busting is needed. Require the existing manager/HMR to report `applied` with no warnings/failure. Profile HMR watches profile patches/manifests, not this private JSON; a simple toggle does **not** itself evict imported module caches. If mounting/unmounting reports `restart-required` rather than `applied`, stop and coordinate an owner-approved restart; never restart ongoing DSH work or start a replacement server as a development side effect.
7. Confirm `remote-status` shows a ready current generation, then verify on the phone: UUID project IDs in sidebar order; more than one project; creation/prompt/stop in the chosen scope; a newly added/renamed project without re-pairing; archived sessions absent. Missing directories should not appear. Never print private config, invitation, transcript or credential contents in logs.

The installed manager implementation at `dsh-plugin-manager/lib/index.js` around 2031–2045 confirms reload is a no-op without `hmr` and reports `restart-required`; the related 1651–1658 path confirms enable/disable reconciliation. HMR availability, module-watch coverage and live loader outcome remain operator preflight checks, not proven by isolated tests.

The source seam is based on installed `dsh-workspace` declarations/compiled implementation: `list()` is synchronous and has no persistence reads, `archivedSessionIds` is a synchronous getter, and `Workspace.status()` is an uncached async stat. After that await the companion rechecks the same active **underlying service identity** and exact visible IDs/paths/titles/order; a concurrent revision fails closed. Cordis 4.0.4 returns a fresh tracing proxy from each `ctx.get()` call even for the same Service. Comparing those proxy references caused spurious `workspace_registry_unavailable` HTTP 503 after successful startup. The source now compares `Symbol.for('cordis.original')` only for provider identity and keeps method calls on the traced proxy; real service loss/replacement still fails closed.

The inspected `dsh-workspace/lib/types/paths.js` defines identity as exact `fs.realpath` string equality, including Windows case. Session-list deduplication is bounded to the current request, never a cross-request authorization cache; the adapter checks the server-authorized workspace ID again before mutations and synchronously validates the registry revision after filesystem awaits/iterator cleanup.

Installed Cordis 4.0.4 `inject` arrays/object maps are required dependencies (an object value `{required:false}` does not make one optional). `ctx.inject` creates a required lifecycle-bound child, not an optional lookup. `ctx.get('workspaceRegistry')` bypasses the property inject guard and defaults to `strict=true`, which checks provider ACTIVE state; explicitly passing `true` is equivalent, while `false` would expose loading/inactive values and is not used. The companion retains its original required services and reads the active registry on every request/check, failing startup/use with a fixed nonsecret error when absent. In registry mode create supplies upstream `workspaceId` rather than `cwd`, so the inspected controller also attaches the new session to that sidebar entry.

Undo removes/disables only that insertion and verifies listener/connector disposal; it must not cancel existing DSH work, erase private SQLite/credentials, regenerate TLS keys or overwrite unrelated concurrent profile edits. Compare the private backup rather than blindly restoring the full old patch. If live disposal is not available, stop and coordinate with the owner.

## Status and invitation/reinvitation

After activation, from the project root:

```powershell
node host/dist/cli.js remote-status --config (Join-Path $private 'host.json')
```

Status is nonsecret. Require a ready current connector generation before pairing. A relay health response alone does not prove route/connector readiness. Pairing output is explicit: direct `pair` requires `--qr` and/or `--output`, while `remote-pair` requires the private `--output` even with QR. Calls without the required opt-in fail before creating any offer/grant. Invitation JSON is never printed to stdout; automation must read the protected file instead.

For an explicitly chosen all-project policy, use wildcard scope explicitly; execution authorizes desktop work in every current/future registered project. The same `all` syntax works for direct `pair`. It is not inferred from registry configuration alone; existing devices need `grant` or a deliberate new pairing:

```powershell
$invitation = Join-Path $private ('invitations/invite-' + [guid]::NewGuid().ToString('N') + '.json')
node host/dist/cli.js remote-pair `
  --config (Join-Path $private 'host.json') `
  --read all --execute all --ttl 300 --qr --output $invitation
```

Legacy explicit-list mode still accepts approved comma-separated IDs validated against its prepared configuration. `all` stores `['*']`, not a snapshot of current IDs; execute `all` requires read `all`, and explicit execution must stay within read scope. Registry-mode standalone CLI accepts only `all` (or omitted execution) because no live Cordis source is available there. Omit `--execute` for read-only; in `grant` this replaces/removes old execution, rather than retaining it. `--output` must be a new exclusive absolute file inside the private invitations directory; never omit it or reuse an existing filename. The CLI publishes the v2 bootstrap grant and waits for the current connector acknowledgement before claiming success; unavailable publication fails safely. Do not print or log the file contents. With `--qr`, the CLI additionally displays the exact bounded `dshm1` zlib/base64url envelope at correction M; maximize the terminal so it never wraps. Oversized QR falls back to the private JSON, without truncation or secret diagnostics. Do not screenshot/share the QR; run `Clear-Host` and clear terminal scrollback after pairing. Transfer/import the file via the approved private mechanism if needed; no manual relay-secret paste is required. Invitations expire after at most 15 minutes and the host offer is single-use.

`grant` updates only active device grants in one SQLite transaction. Device credential, relay access IDs/tokens/expiry and receipts are untouched. Every subsequent request/admission/replay uses current grants. Existing SSE streams recheck periodically (normally within one second plus source I/O): removing read closes them; removing execute republishes `canExecute:false` even when no new message arrives. Already-dispatched work is not stopped.

## Synthetic multi-project screenshots

Build the host first, then explicitly add `--multi-project` to the existing local demo invocation:

```powershell
node host/dist/demo.js --state-dir '<PRIVATE_SYNTHETIC_DEMO_DIRECTORY>' --port 0 `
  --invitation-file '<NEW_PRIVATE_DEMO_INVITATION_JSON>' --multi-project
```

This opt-in fixture supplies five clearly labelled Russian demo projects, twelve sessions (two running), and multi-paragraph Markdown/Kotlin samples. It captures the demo start time once: both running sessions are updated today, with last user messages three and five minutes earlier; other sessions span today, yesterday, recent days and older dates. Existing timestamps stay stable on reads. Near a week boundary some recent-day rows naturally group into the preceding week. It never connects to DSH or a model. Default single-project `FIXTURE` constants/output are unchanged. Only this opt-in invitation adds `demoFixture:{markdownSessionId:"demo-fund-plan",markdownAnchor:"План портала фонда",filterWorkspaceId:"demo-fund"}`; the same stable nonsecret metadata is exported as `MULTI_PROJECT_FIXTURE`. The anchor is visible text after Markdown rendering and the filter workspace has three sessions. Treat the invitation's pairing token as secret even though the metadata is synthetic.

Expired/failed pairing requires another **new** output file/offer, not replaying the consumed invitation or changing TLS identity. For a lost pairing response, inspect/revoke the possible orphan device before issuing a new offer. Use `devices --config ...` / `revoke --config ... --device <id>` as applicable. Keep files private and remove obsolete invitations explicitly after use; local Android forget is not remote revocation.

## Isolated checks and limitations

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/mobile-remote-setup.test.ps1
```

The [CLI tests](../tools/mobile-remote-setup.test.ps1) use synthetic credentials/workspace and real OpenSSL in a unique owner-private DSH cache directory with overridden child LOCALAPPDATA. They exercise initialization/idempotency, partial-state preservation, changed config/certificate rejection, weak input ACL, invalid URLs, relative paths and junction rejection. They never initialize the real owner directory, edit a profile, issue an invitation or access a relay. Cache evidence contains synthetic data only.

Local setup tests do not prove live plugin mounting, relay deployment, native v2 pairing, Wi-Fi/mobile transitions or production readiness. Review and activate only after those respective gates. The relay remains an opaque inner TLS transport, not a public raw DSH port or a general-purpose proxy.

Official crypto references consulted through Context7: [OpenSSL req/addext](https://docs.openssl.org/3.5/man1/openssl-req/), [Node X509Certificate](https://nodejs.org/docs/latest-v24.x/api/crypto.html#class-x509certificate). No handwritten ASN.1, custom TLS cryptography or extra crypto package is used.
