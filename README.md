# DSH Mobile

A native Android companion for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). Browse permitted chats, send text tasks and follow their progress from your phone while execution, tools, subscriptions and canonical history stay on your computer.

**Independent and unofficial — not a DeepSeek product. Development preview, not a production-ready remote-access service.**

## Interface

Russian-first Kotlin / Jetpack Compose UI with English resources, dark/light/system themes and native Markdown rendering.

Actual native captures with synthetic demo data; no real chat, model or production host is shown.

<p>
  <img src="docs/screenshots/home-dark.png" width="240" alt="Native chat list in dark theme">
  <img src="docs/screenshots/chat-markdown-running.png" width="240" alt="Synthetic running conversation with Markdown">
  <img src="docs/screenshots/new-chat-sheet.png" width="240" alt="Native new-chat project selection sheet">
</p>

## Features

- Pair one trusted computer through a short-lived, one-use invitation; confirm its identity before connecting.
- Browse permitted workspaces and sessions, create a chat and choose an available agent preset.
- Send text prompts, observe authenticated live replacement snapshots and request cancellation of active work.
- Keep drafts and original command IDs through lifecycle changes; reconcile uncertain delivery without blindly sending a second task.
- Search the received chat list, filter projects, copy selectable messages/code and render a bounded Markdown subset.
- Connect directly over HTTPS, or use an optional self-hosted opaque relay for outbound connections from both endpoints.

## Architecture

```text
Phone → optional WSS relay ← desktop connector → companion plugin → DSH
           opaque inner TLS       loopback HTTPS      in-process API
```

The companion is a host-level Cordis plugin, not a WebView, a raw DSH RPC proxy or a second agent runtime. Direct connections use HTTPS and authenticated SSE. The relay forwards inner TLS bytes and never chooses an arbitrary desktop target. See [Architecture](docs/ARCHITECTURE.md), [API](docs/PROTOCOL.md) and [Relay protocol](docs/RELAY_PROTOCOL.md).

## Security model

- HTTPS requires certificate validity/hostname checks and the invitation's SPKI pin. Import invitations through a trusted channel: a substituted whole invitation cannot authenticate your computer.
- Pairing offers expire and are single-use. Each device receives its own credential and separate read/execute grants; the host supports revocation.
- Android stores credentials in Keystore-protected encrypted state and excludes them from backup. Host configuration, TLS/signing keys, state and invitations belong outside Git.
- The optional relay uses separate outer capabilities and public WSS trust. It can see routing metadata, IPs, timing and sizes, and can deny service; it does not receive inner HTTP/chat plaintext.
- **Workspace filtering is not a filesystem sandbox.** DSH tools may have broader permissions, and permitted transcripts can contain cross-workspace material. Authorize execution only for trusted devices.
- Delivery receipts indicate admission, not task completion. No exactly-once or permanent background-connection guarantee is made.

Read [Security](docs/SECURITY.md) before exposing a companion or relay. Do not publish the raw DSH Web port.

## Requirements

- Android 8.0 / API 26 or later; compile/target SDK 36.
- Node.js 24.x and npm for the host and relay (built-in SQLite).
- JDK 21 recommended; Android SDK platform 36 and build-tools 36.0.0.
- DeepSeek Harness **0.2.0-rc.2**, explicitly declared in plugin configuration. This is the only supported declaration, not automatic runtime detection; revalidate compatibility before upgrading DSH.
- Remote Windows setup/signing helpers additionally use Windows PowerShell 5.1 and installed OpenSSL 3. A relay deployment requires operator-managed Linux, Docker, Caddy and user systemd.

## Build and test

```sh
cd host
npm ci
npm run check
cd ../relay
npm ci
npm run check
cd ../android
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows, from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& ./tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'"
node --test tools/*.test.mjs tools/dsh-canary/*.test.mjs
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/android-release.test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/mobile-remote-setup.test.ps1
```

The setup tests require `DSH_HOME` pointing at your installation's home for a disposable sibling cache; they override child `LOCALAPPDATA`, not your actual companion state. Host tests dependent on installed Cordis skip unless you explicitly supply `DSH_MOBILE_CORDIS_MODULE`. Core tests still run without DSH installed. See [Build](docs/BUILD.md), [isolated DSH canary](docs/DSH_CANARY.md), [native acceptance procedure](docs/ANDROID_ACCEPTANCE.md) and [signing your own APK](docs/ANDROID_RELEASE.md).

## Host plugin setup

Build/check the host first. Start with an isolated composition and the disabled [Cordis patch example](examples/cordis.patch.yml); do not replace a running profile or restart ongoing work as a build side effect.

**Recommended: registry mode**, with `"workspaceSource":"dsh-registry"` and no nonempty `workspaces` array in your private host configuration. It follows registered DSH projects and sidebar order without arbitrary filesystem discovery. Grant policy remains explicit: `--read all` and optional `--execute all` cover current and future registered projects. Use a private explicit workspace list instead if you need a narrower project set; the preparation helper requires `-WorkspacesPath` and never guesses a project directory.

For remote access, prepare private TLS/connector state, review an additive plugin insertion, then verify connector readiness before issuing a new invitation. [Remote setup](docs/REMOTE_SETUP.md) and [DSH integration](docs/DSH_INTEGRATION.md) describe activation, grants, revocation and undo. These are manual operator steps, not automatic installation.

## Relay self-hosting

No shared public relay is bundled or promised. Supply your own canonical WSS URL, for example `wss://relay.example.com/dsh-mobile-relay`, and provision a private route capability on that relay. The desktop connector makes an outbound connection; the phone needs no separate VPN app or home-port forwarding.

The [relay package](relay/README.md) documents its CLI. [Bounded deployment](docs/RELAY_DEPLOYMENT.md) describes a hardened container, an additive Caddy namespace, pinned identities and independent rollback before any production mutation. Deployment, certificate rotation and device/network acceptance require separate operator review. Acceptance probes require the exact approved URL via `--approved-relay-url` or `RELAY_PUBLIC_URL`; they do not allow arbitrary public endpoints.

## Status and limitations

- Development preview; unit/fixture/emulator success does not establish physical-phone, roaming, Play Protect or production-profile readiness.
- One trusted host. The computer, DSH, companion and optional relay must remain available.
- No push/background-completion notifications, attachments/downloads, structured question replies or approval actions. No permanent Android background socket or offline execution.
- History is bounded to the current snapshot. Ambiguous message replacements fail closed; some compacted chats require the desktop until paging is implemented.
- Remote invitations last at most 15 minutes; device relay access initially lasts 365 days and then requires re-pairing. The setup TLS identity lasts 825 days and needs a reviewed rotation procedure.
- No guarantee of access from every network, metadata invisibility, exactly-once execution or cancellation of a specific historical run.

See [implementation and acceptance plan](docs/PLAN.md) and [Contributing](CONTRIBUTING.md).

## По-русски

DSH Mobile — независимый неофициальный нативный Android-компаньон для DeepSeek Harness. Читайте разрешённые чаты, отправляйте текстовые задачи и следите за выполнением с телефона; модели, инструменты, подписки и основная история остаются на компьютере. Интерфейс ориентирован прежде всего на русский язык.

Это предварительная версия для разработки. Привязка выполняется через доверенное одноразовое приглашение с проверкой компьютера; удалённый режим использует ваш собственный relay. Push, вложения, ответы на вопросы и approvals пока не поддерживаются. Фильтрация проектов — не sandbox для инструментов DSH. Инструкции: [сборка](docs/BUILD.md), [подключение](docs/REMOTE_SETUP.md), [безопасность](docs/SECURITY.md).

## License

[MIT](LICENSE), Copyright (c) 2026 Pavel Logachev. Third-party dependencies retain their own licenses.
