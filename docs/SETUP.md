# Install DSH Mobile on your PC and Android phone

This is an **agent runbook** for an existing Windows DeepSeek Harness installation. Read a section, inspect the stated evidence, ask the owner at each numbered checkpoint, then perform only the approved action. Do not run every command as one unattended script. The PC must stay on and DSH must be running; the phone is a remote client, not the agent host.

Start with **direct HTTPS over the same LAN**. For internet use, **Tailscale direct HTTPS** is the simplest supported option. A self-hosted relay is an advanced option; this repository supplies no shared relay, public endpoint or hosted service. Never expose the raw DSH web listener, open a router port, disable TLS verification, or install a system trust root.

## 1. Release and installation consent

**ASK THE USER 1:** May I inspect your DSH/Node versions and install this companion for the current Windows account? Do you want same-LAN use first, Tailscale remote use, or an explicitly self-hosted relay? Installation and activation are separate approvals.

Use the [repository Releases page](https://github.com/pavel-logachev/dsh-mobile/releases). Download a **signed, installable Android APK**, its SHA-256 file, `dsh-mobile-host-<version>.zip`, and its SHA-256 file from the **same reviewed release/tag**. Do not install an `UNSIGNED-verification.apk`: it is a CI verification artifact, not an installable release. Do not use a debug APK as a production shortcut. If these signed/reviewed assets do not exist, **stop**: request a release from the maintainer; do not invent a download URL or sign somebody else's app with a temporary key.

Verify each downloaded artifact before extracting or executing anything:

```powershell
$zip = '<ABSOLUTE_DOWNLOADED_HOST_ZIP>'
$apk = '<ABSOLUTE_DOWNLOADED_SIGNED_APK>'
(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
# Compare exactly with the release's .sha256 files. A mismatch means STOP.
```

A checksum detects corruption/substitution relative to the reviewed release; a checksum beside an untrusted file is not an independent signature. Keep the APK signing certificate stable for Android upgrades. Ask before enabling Android's per-app “Install unknown apps”; revoke that permission after installation. Never ask for the user's DSH provider credentials, invitation or private key in chat.

## 2. Verify the existing desktop, without changing it

```powershell
node --version
Get-Command dsh
dsh --version
$env:DSH_HOME
Test-Path (Join-Path $env:DSH_HOME 'profiles/web/package.json')
Get-NetTCPConnection -State Listen -LocalPort 19445 -ErrorAction SilentlyContinue
```

Require **Node 24.x** and an exact DSH version in this explicit allowlist:

| DSH | Cordis inspected | Evidence |
| --- | --- | --- |
| `0.2.0-rc.2` | `4.0.4` | declarations + compiled JS + isolated full/registry canaries |
| `0.2.1-alpha.1` | `4.0.5-alpha.1` | declarations + compiled JS + isolated full/registry canaries |

Do not accept an arbitrary semver range, “latest”, matching method names, or an unverified version string. The plugin requires an explicit `dshVersion` declaration. The installer checks the real `dsh --version`; standalone setup defaults to auto-detection and validates its output. If `dsh` is not on PATH, locate the owner's already-installed trusted launcher and pass `-DshCommand '<absolute launcher>'` to the installer. Do not download another DSH runtime or change a running installation just to make preflight succeed.

Confirm the correct `DSH_HOME` with the owner; do not guess from this source checkout. The installer requires an existing web profile and never creates one. If port 19445 is occupied, identify it without terminating it; choose another approved companion port and use it consistently. Never replace the existing DSH process/service or start a second web server. HMR package presence is not proof that profile hot reload is active.

### Prerequisite download and installation — separate consent

First inspect how the owner starts DSH, not just the `node` on PATH. Read the resolved trusted launcher if it is a script; inspect the existing DSH process's executable/command line locally (do not post unrelated command lines into chat):

```powershell
Get-Command node, dsh -ErrorAction SilentlyContinue | Select-Object Name, Source
Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
  Where-Object { $_.CommandLine -match '@deepseek-ai[\\/]dsh[\\/]lib[\\/]bin\.js' } |
  Select-Object ProcessId, ExecutablePath, CommandLine
# For a packaged launcher, inspect its existing process/service configuration instead.
```

DSH may use its **own Node**, a bundled executable or an absolute path rather than PATH. Conversely, an existing DSH may use the same system Node as the companion. Verify that executable's version as well: the plugin executes inside DSH, so a Node 24 on PATH alone does not prove that the mounted plugin has Node 24's SQLite support. A separate Node 24 for administration does not upgrade DSH's in-process runtime. **Do not blindly replace the Node that DSH runs on**, change its launcher/PATH, or restart it to satisfy companion preflight. If the proposed installer affects that executable, stop and review an owner-approved migration or a separate Node 24 installation with a process-local PATH for the companion.

**ASK THE USER 1a:** A prerequisite is missing or incompatible. May I download and install/upgrade the named Node 24 or OpenSSL 3 package from the source below, with the displayed version, architecture, location, PATH/elevation impact and any effect on the existing DSH launcher? This is separate from companion consent; do not install anything before the answer. If compatible copies already exist, reuse them without installation.

- **Node 24 LTS Windows installer:** use [nodejs.org Downloads](https://nodejs.org/en/download), explicitly select **v24.x LTS**, Windows and the PC's x64/ARM64 architecture, then the `.msi` installer. Do not choose Current or another LTS major. The [official v24.21.0 LTS release page](https://nodejs.org/en/blog/release/v24.21.0) supplies the Windows installers and signed SHASUMS; follow [Node's binary-verification instructions](https://github.com/nodejs/node#verifying-binaries), compare `Get-FileHash -Algorithm SHA256` with the verified SHASUMS and review `Get-AuthenticodeSignature` before executing the MSI. Prefer the current reviewed 24.x security patch, not a permanently pinned old patch.
- **Optional winget:** the verified [Microsoft community manifest for `OpenJS.NodeJS.LTS` 24.19.0](https://github.com/microsoft/winget-pkgs/blob/master/manifests/o/OpenJS/NodeJS/LTS/24.19.0/OpenJS.NodeJS.LTS.installer.yaml) points to nodejs.org's x64/ARM64 MSI and its hashes. That package ID can move to a future LTS major. Run `winget show --id OpenJS.NodeJS.LTS --exact --source winget` locally first and require the displayed version to be **24.x** with a nodejs.org installer; do not assume the unqualified LTS command still maps to 24. After the consent above, `winget install --id OpenJS.NodeJS.LTS --exact --source winget --version <REVIEWED_24_X_VERSION>` is an alternative to the reviewed MSI. If the current community manifest lags an official security patch, use the official MSI rather than installing the older patch just for convenience.
- **OpenSSL 3:** first reuse a trusted [Git for Windows](https://gitforwindows.org/) installation's bundled `openssl.exe`. Its [release notes](https://github.com/git-for-windows/build-extra/blob/main/ReleaseNotes.md) list the bundled OpenSSL version. Depending on the Git version/architecture, the executable is under `mingw64/bin`, `ucrt64/bin` or `usr/bin`; verify the exact installed path/version. Do not install/upgrade Git solely for this without consent or add all of its directories to global PATH. If Git/OpenSSL is absent, the owner may approve the Git installer from that official site, or [Shining Light Productions' Windows OpenSSL](https://slproweb.com/products/Win32OpenSSL.html), an independent distributor: choose a maintained **3.x** build for the PC architecture (the Light package includes the CLI), verify its [published SHA-256](https://github.com/slproweb/opensslhashes/) and installer signature, and keep DLLs in the package directory rather than copying them into Windows system directories. Do not download an arbitrary search-result binary or OpenSSL 4 for this 3.x-only setup.

After any approved installation, open a **new PowerShell shell** so command discovery sees the intended executable, and verify again before preparing TLS:

```powershell
Get-Command node, openssl -ErrorAction SilentlyContinue | Select-Object Name, Source
node --version                  # Must be v24.x.
openssl version                 # Must be OpenSSL 3.x when it is on PATH.
# For bundled Git/OpenSSL not on PATH, use the exact installed path instead:
$openssl = '<ABSOLUTE_VERIFIED_OPENSSL_EXE>'
& $openssl version              # Must be OpenSSL 3.x; do not change global PATH.
# If using a separate companion Node, verify its absolute executable in this shell too.
```

`-OpenSslPath $openssl` / CLI `--openssl $openssl` accepts that explicit installed executable. OpenSSL is needed only when creating TLS identity. Node 24's `X509Certificate` parses/verifies certificates, **not generates them**; Node's internally linked OpenSSL is not the `openssl.exe` prerequisite. Setup uses OpenSSL's P-256/SHA-256 generation, not handwritten ASN.1 or an extra crypto implementation. This is separate from the APK signing key. No prerequisite is installed by the companion scripts.

## 3. Choose addresses and approve permissions

**ASK THE USER 2:** Which exact hostname/IP will the phone use? May the companion offer **read access to all current and future registered DSH projects**? May it also **execute commands in all of them**, or should the phone be read-only?

Registry mode is the default: `workspaceSource: "dsh-registry"`, with no explicit `workspaces` list. It follows the live DSH sidebar's workspace UUIDs/order/names, added/renamed/removed projects, and hidden archived sessions. Missing directories are omitted; at most the first 100 registrations are considered. This is not filesystem discovery. A nonempty explicit list is mutually exclusive with registry mode; a restricted manual subset is the advanced explicit-list path.

**Permissions are filtering, not a filesystem sandbox.** DSH tools retain their normal desktop powers; transcript text may refer to other paths or contain sensitive data. Read `all` stores a wildcard for future registrations; execution is a distinct approval. Never infer execute permission from installing the app or selecting registry mode. The standalone registry CLI permits only `all` because it does not open private DSH registry storage. Omit `--execute` for read-only.

For LAN, inspect the owner's actual active Private network adapter and choose a stable reachable DNS name or IPv4 address. Do not put placeholders into live commands. For Tailscale, inspect the already-approved tailnet as described in section 8. The **first** `-Hosts` value becomes the invitation URL; every value is a certificate SAN. Include the LAN and Tailscale names/IPs the owner intends to use **before identity generation**. Use lowercase DNS names or canonical literal IPs, not a URL, path, wildcard, port, IPv6 zone ID, or injection string.

## 4. Prepare the immutable host package

**ASK THE USER 3:** May I create the private companion directories and stable TLS identity now? This does not activate DSH, open a listener, add firewall rules, or change OS trust.

After verifying the zip checksum, extract its **installer script** into a trusted local review directory. Read it before running it. The zip includes built JS, package metadata, MIT/bundled-dependency licenses, the sole runtime dependency `ws`, and a hashed inventory; no Git checkout, npm install, TypeScript or Android SDK is needed on the recipient PC.

```powershell
$installer = '<ABSOLUTE_REVIEWED_INSTALL_HOST_PS1>'
$hosts = @('<ACTUAL_PRIMARY_LAN_OR_TAILSCALE_HOST>', '<OPTIONAL_ADDITIONAL_SAN>')
# Remove the second element if not needed. Substitute approved real values locally.
& $installer -ZipPath $zip -Hosts $hosts -WhatIf
& $installer -ZipPath $zip -Hosts $hosts
# Optional: -OpenSslPath '<TRUSTED_OPENSSL_EXE>' -DshCommand '<TRUSTED_DSH_LAUNCHER>'
```

Use a PowerShell session allowed to run the reviewed script. A one-process `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ...` is possible; do not change system execution policy. For multiple hosts, direct `& $installer -Hosts $hosts` preserves the array reliably.

The resulting layout under `%LOCALAPPDATA%/DSHMobile` is:

- `plugin/<version>/`: the **whole immutable build**. It is verified, never overwritten, and imported through a new `file:///.../dist/plugin.js` URL on upgrade. Do not copy just one module or modify a mounted build.
- `host/host.json`, `host/tls-key.pem`, `host/tls-cert.pem`: private config and stable P-256 TLS identity, valid for 825 days with `CA:false`, digitalSignature, serverAuth, and exact DNS/IP SANs.
- `host/state/`, `host/invitations/`: private runtime SQLite and fresh one-use invitation output.
- `backups/`: private profile-patch backups; `install-receipt.json` records managed versions, never tokens.

New private directories/files use the current owner's Windows SID only. Existing weak ACLs, Git directories, redirected/reparse ancestors, UNC and alternate data streams are refused, **not repaired silently**. Initialization refuses partial identity state. Repeat setup validates/preserves the same key; it never silently rotates credentials. Back up the complete private state securely outside Git; do not copy a live SQLite database inconsistently. Key loss, expiry or changed required SANs needs a separate reviewed identity migration and re-pairing, not deletion/retry.

For source users, the equivalent private preparation is:

```powershell
$config = '<ABSOLUTE_NEW_OWNER_PRIVATE_DIRECTORY>/host.json'
node host/dist/cli.js setup-direct --config $config `
  --host '<PRIMARY_HOST>' --host '<ADDITIONAL_SAN>' --dsh-version auto
# Default port 19445; repeat --host for each SAN; omit unused additional hosts.
# setup-direct is a generator, not a server launcher or profile installer.
```

## 5. Activate only the reviewed additive insertion

**ASK THE USER 4:** May I add the displayed companion block to this exact existing web profile patch and wait for HMR? May I keep a private backup? No DSH restart is authorized by this approval.

Review the installer's printed `DSH-MOBILE-COMPANION-BEGIN/END` block. It inserts only `id: dsh-mobile-companion`, with the absolute immutable module URL and `{dshVersion,configPath}`. No TLS key, inline host JSON, provider credential, or relay connector token belongs in YAML. Unrelated existing bytes are preserved; malformed/duplicate/unmarked legacy companion rows fail closed. YAML `!!js` expressions are parsed inertly for validation, not executed by the installer. The script checks for concurrent patch changes and backs up before mutation.

```powershell
& $installer -ZipPath $zip -Hosts $hosts -Activate
```

The installer waits up to **60 seconds** for the chosen companion port. A listener is a readiness hint, not authentication proof: continue with status and native pairing. If it does not appear, the script says that a **DSH restart may be needed**; it never restarts, cancels sessions, kills processes or replaces a server. Stop and ask the owner before any later restart. A source/private JSON change alone is not a reload. Profile HMR watches patches/manifests, not this config or arbitrary source files; Node's module cache is why whole versioned builds and fresh URLs matter.

## 6. Optional LAN firewall rule — separate consent and rollback

**ASK THE USER 5:** Is this actually the owner's trusted **Private** LAN, and may I add this narrowly scoped inbound TCP rule? May I elevate for that one command? Do not change the network category to Private automatically or add a Public-profile rule.

Inspect `Get-NetConnectionProfile`. If the trusted LAN is not Private, stop and let the owner decide. If connectivity already works, do not add a rule. The reviewed rule exposes **only the companion**, never port 3080 or other DSH endpoints:

```powershell
$rule = 'DSHMobile-Companion-Private'
# Require no preexisting rule with this name; otherwise review it without replacing it.
Get-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue
# In an owner-approved elevated PowerShell only:
New-NetFirewallRule -Name $rule -DisplayName 'DSH Mobile companion (Private LAN)' `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 19445 `
  -Profile Private -RemoteAddress LocalSubnet
# Exact rollback, only if THIS procedure created the named rule:
Remove-NetFirewallRule -Name $rule
```

Substitute an approved alternate port consistently. Record who created the rule so rollback never deletes another application's rule. No router forwarding, public ingress, VPN change, trust-root installation or automatic firewall mutation is performed by the installer.

## 7. Pair by scanning the PC QR

**ASK THE USER 6:** May I display a short-lived one-use pairing QR with the approved read/execute scope on this private screen now? Is terminal transcription/log capture off? The QR is a credential; do not screenshot, forward, paste into chat, or retain it in logs.

`pair` rejects a call without explicit `--qr` and/or `--output` **before creating any offer**. `remote-pair` is stricter and requires `--output` (QR is optional). Invitation JSON is never printed to stdout; automation must read the exclusive private output file, not parse command stdout. QR/file output contains a credential and needs the consent above.

Select the installed version directory, without using a random source build:

```powershell
$plugin = '<EXACT_LOCALAPPDATA_DSHMOBILE_PLUGIN_VERSION_DIRECTORY>'
$cli = Join-Path $plugin 'dist/cli.js'
$config = Join-Path $env:LOCALAPPDATA 'DSHMobile/host/host.json'
node $cli verify-config --config $config
node $cli remote-status --config $config
Get-NetTCPConnection -State Listen -LocalPort 19445 -ErrorAction SilentlyContinue
node $cli devices --config $config
# Direct read-only:
node $cli pair --config $config --read all --ttl 300 --qr
# OR, only after explicit execution approval:
node $cli pair --config $config --read all --execute all --ttl 300 --qr
```

The terminal QR is white-backed UTF-8 half-block rendering with a four-module quiet zone. Maximize the terminal and reduce font size until **every row is visible without wrapping**; P-256 direct invitations typically need roughly 125 columns. Android: open Pairing → scan the PC QR → review the displayed host/trust information → confirm. Scanning alone must not silently pair. The native CameraX/ZXing scanner is offline, has no Google Play services dependency, and requests optional camera permission only when the user chooses to scan. If permission is denied or the camera is unavailable, keep private JSON import/manual paste available; never bypass verification.

The payload is the exact [QR invitation envelope](PROTOCOL.md): `dshm1:` plus canonical unpadded base64url of **zlib-wrapped DEFLATE** UTF-8 invitation JSON. Host and Android retain bounds; there is no raw DEFLATE/gzip/dictionary/truncation. QR correction is **M**; maximum byte payload is 2331 characters. The short-lived v1/v2 invitation retains the certificate PEM and SPKI pin. Validity dates, hostname and pin checks still run; scanning does not disable TLS checks.

If terminal scanning is impractical or a long certificate/relay invitation exceeds QR capacity, choose a new private JSON filename:

```powershell
$invite = Join-Path (Split-Path $config) ('invitations/invite-' + [guid]::NewGuid().ToString('N') + '.json')
node $cli pair --config $config --read all --ttl 300 --qr --output $invite
# Add --execute all ONLY for the approved scope.
```

A capacity failure falls back to that **exclusive, owner-private file** without truncating or logging its contents. Transfer/import it through an explicitly approved private route; never a public paste service. `--output` must be inside the private config's `invitations` directory and must not already exist. Remove obsolete invitations only after owner approval. After pairing use `Clear-Host` **and clear terminal scrollback** (Clear-Host alone may leave it); do not assume that it erases transcripts/screenshots. TTL defaults to 300 seconds for direct pairing and is never more than 900. Expired/consumed invitations need a new offer, not key rotation. If a response was lost, inspect/revoke any possible orphan device before issuing another offer.

## 8. Tailscale direct HTTPS from outside the LAN

**ASK THE USER 7:** May we use/install Tailscale on both devices and sign them into the owner's tailnet? Which tailnet devices/users may reach the companion? Tailnet membership and access rules are the owner's decision, not a shared service supplied here.

Follow [Tailscale's Windows installation](https://tailscale.com/docs/install/windows) and [Android installation](https://tailscale.com/docs/install/android). Both devices must be in the approved tailnet with access allowed to the chosen companion port. Inspect `tailscale status` and `tailscale ip -4`; use the actual approved MagicDNS full name or 100.x address, not the web-GUI address. Follow [MagicDNS documentation](https://tailscale.com/docs/features/magicdns). Do not enable Funnel, public Serve, public ACL grants, router forwarding, or disable TLS checks.

Supply the intended MagicDNS/100.x hosts as SANs **before setup**; the first host is the primary URL. Keep direct mode: no relay fields are added. Existing TLS identity may already cover a second SAN: issue a fresh invitation with `pair ... --url 'https://<ACTUAL_TAILSCALE_SAN>:19445' --qr`. Host checks that hostname/IP against the same certificate. A SAN is not reachability proof; verify routing/DNS separately.

Tailscale's adapter may not have Windows Private network category. The LAN `LocalSubnet` rule is not a universal tailnet rule. If traffic is blocked, inspect the actual adapter/profile and ask separately for a narrow rule limited to the owner's approved tailnet/device source range and companion port; do not silently create an Any/Public rule or relabel a network. Never expose raw DSH. Switch the phone to mobile data with Tailscale enabled and verify the same HTTPS flow before calling remote acceptance complete. If the existing certificate lacks the chosen host, stop for a reviewed certificate migration; do not overwrite the stable key in place.

## 9. Verify the actual native flow

**ASK THE USER 8:** May I run one clearly labelled harmless test in an approved project? Execution may consume model quota. Do not infer this approval from the installation.

After scanning, confirm a new device in `devices`; do not print its bearer token. On Android verify:

1. More than one registered project appears in the expected sidebar order; the current workspace/session is not replaced with a mock fixture.
2. The phone can read an existing approved session. Add/rename a test project on the desktop: it appears without re-pairing/reloading. Archived sessions stay hidden; missing directories stay omitted.
3. If execution was approved, create one synthetic conversation, send a harmless prompt, observe provisional output become the authoritative final text, and stop with the current observation. Desktop approvals/questions still require the desktop; the phone must not pretend they are idle.
4. Disconnect/reconnect while work runs: desktop execution continues; reconnect replaces history with an authoritative snapshot. Do not automatically resend an uncertain command with a new request ID.
5. For Tailscale, repeat on mobile data with the LAN disconnected. A local unit test or canary is **not** physical-phone/network acceptance.

Troubleshooting:

| Symptom | Safe next step |
| --- | --- |
| Unsupported DSH version | Stop; inspect real version; require declarations/JS/canary review before extending the allowlist. |
| No listener after activation | Inspect existing profile/HMR logs without transcripts; ask whether a restart can be scheduled. Never start a replacement server. |
| `workspace_registry_unavailable` / HTTP 503 | Confirm active DSH registry service; do not fall back to guessed paths or weaken its identity/revision checks. |
| TLS date/hostname/pin error | Check PC/phone clocks, exact invitation URL/SAN and original certificate. No insecure retry or OS-root workaround. |
| QR not decoded | Ensure no wrapping/cropping, white quiet zone, correct font/brightness; regenerate only a fresh offer or use private JSON. |
| QR capacity failure | Use exclusive private JSON fallback; never remove the certificate/pin or truncate to fit. |
| Pairing expired/already used | Inspect devices, revoke orphan if necessary, issue a fresh short-lived invitation. |
| Wi-Fi reachable but mobile data is not | Inspect Tailscale membership/routing/MagicDNS/approved firewall policy; never open the router/public DSH port. |
| Delivery `uncertain` | Inspect desktop/receipt first; never blindly resend a side-effecting prompt. |

## 10. Upgrade, rollback, revoke and uninstall

**ASK THE USER 9:** May I unmount just the companion, preserve private state/credentials, then load the approved immutable new version? Ongoing DSH agents are not cancelled, but the phone's connection will briefly drop. Restart and key rotation remain separate approvals.

Verify the new release hashes and install using its reviewed script. Use **`-Upgrade -Activate`**: it backs up the profile, replaces the marked block with an unmounted placeholder, waits for listener closure, and only then mounts the entire new version through a fresh module URL. If closure does not happen, stop at the placeholder and coordinate an approved restart; do not remount onto an occupied port.

```powershell
& $installer -ZipPath '<VERIFIED_NEW_HOST_ZIP>' -Upgrade -Activate
# Managed previous version, after separate consent:
& $installer -Rollback -Activate
# Revoke a lost phone (local forget is NOT remote revocation):
node $cli devices --config $config
node $cli revoke --config $config --device '<DEVICE_UUID>'
# Unmount only the companion; private files and backups are intentionally retained:
& $installer -Uninstall -Activate
```

Repeat installation/activation/uninstall is idempotent. Immutable payload mismatch/corruption fails; a same-version zip is never overwritten to bypass it. The receipts and backups are for managed rollback/comparison, not blind full-profile restoration over later unrelated edits. Revoke affected devices before losing administration access. Undo only a firewall rule this procedure created. Deleting private TLS/config/SQLite/invitations/versions requires a **separate owner-approved secure cleanup** after disposal; the installer does not erase them.

## 11. Advanced: optional self-hosted relay

Use [relay deployment](RELAY_DEPLOYMENT.md) and [private remote setup](REMOTE_SETUP.md) only after reviewing their separate infrastructure/credentials/activation gates. The operator supplies their own server, WSS URL, private route and connector capability. There is no hosted/shared relay. The PC binds the inner companion to loopback; the relay forwards bounded opaque TLS records, not DSH provider credentials or plaintext API traffic.

For v2 pairing, require `remote-status` to show a fresh ready connector generation, then use:

```powershell
$invite = Join-Path (Split-Path $config) ('invitations/remote-' + [guid]::NewGuid().ToString('N') + '.json')
node $cli remote-pair --config $config --read all --ttl 300 --qr --output $invite
# --execute all only after scope approval. Connector ACK is required before success.
```

The private JSON fallback is compulsory for remote pairing; QR is additionally displayed if capacity permits. A relay health page alone proves neither connector readiness nor native pairing. Physical-phone internet-route acceptance remains a separate check.
