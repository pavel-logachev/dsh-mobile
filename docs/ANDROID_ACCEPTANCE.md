# Android fixture and isolated DSH acceptance

## Scope and present verification

This suite uses the real `MainActivity`, its `MobileViewModel`, the actual `createMobileRepository` factory, Android Keystore storage, HTTP requests and SSE through `adb reverse`. It does not replace the repository or set artificial Compose content. Two explicit upstream modes are admitted: `fixture` (local synthetic host) and `isolated-dsh-canary` (actual installed DSH controllers with an isolated deterministic official model adapter). **A fixture PASS is not a real DSH canary; neither mode proves external model execution, production profile mounting, physical-device acceptance, TLS acceptance or production readiness.**

Implementation files:

- [MobileAcceptanceTest.kt](../android/app/src/androidTest/java/dev/dshmobile/app/acceptance/MobileAcceptanceTest.kt)
- [android-acceptance.ps1](../tools/android-acceptance.ps1)

Check Windows PowerShell 5.1 AST parsing, native argv quoting, safety guards and ignored output paths before execution. Process-scoped `-ExecutionPolicy Bypass` does not change persistent policy. The closed drawer may retain offscreen duplicate title semantics: assert exactly one displayed active title. Disabled-create assertions must require at least one matching control and verify every match is disabled, since drawer and empty-state controls can share a tag. Keep run-specific timings and receipts private.

## Dependencies (owned by parent/build worker)

- `defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- `androidTestImplementation(platform(libs.androidx.compose.bom))`, matching app BOM `2025.08.01` (Compose UI test `1.9.0`).
- `androidTestImplementation("androidx.compose.ui:ui-test-junit4")`
- `androidTestImplementation("androidx.test.ext:junit:1.3.0")`
- `androidTestImplementation("androidx.test:runner:1.7.0")`
- `androidTestImplementation("androidx.test:rules:1.7.0")`
- `androidTestImplementation("androidx.test:core:1.7.0")`

No `ui-test-manifest` or synthetic test Activity is required: `createEmptyComposeRule` synchronizes Compose while `ActivityScenario` launches/closes/recreates the production Activity. Test action labels use tags, not translated Russian/English UI strings. Assertions on fixture content use synthetic backend data, which is intentionally not translated.

Official references: [Compose test setup](https://developer.android.com/develop/ui/compose/testing), [external-work synchronization](https://developer.android.com/develop/ui/compose/testing/synchronization), [semantics selectors](https://developer.android.com/develop/ui/compose/testing/apis). Context7 retrieved these before implementation. External network completion uses `waitUntil`, followed by Compose idle; there are no blind sleeps.

## Local secret configuration

The parent starts a managed isolated host, obtains a fresh one-use invitation, and writes an envelope in an ignored local directory (for example `artifacts/local-fixture/acceptance-config.json`). Do not commit it, paste its contents into a report, print it, or put the invitation/token in `am instrument -e` arguments.

Envelope schema (the invitation below is a placeholder, never a reusable fixture secret):

```json
{
  "version": 1,
  "mode": "fixture",
  "invitation": {
    "version": 1,
    "baseUrl": "http://127.0.0.1:PORT",
    "pairingToken": "GENERATED_LOCALLY_FOR_ONE_RUN"
  },
  "workspaceId": "demo",
  "existingSessionId": "demo-session",
  "existingMessage": "This is a synthetic conversation. No model or DSH runtime is connected.",
  "createdSessionTitle": "Demo conversation",
  "expectedAssistantText": "Synthetic demo answer. No model was called."
}
```

For the isolated actual DSH mode, start the guarded serve process following [DSH_CANARY.md](DSH_CANARY.md), not the demo host. The nonsensitive `ready.json` created by [serve.mjs](../tools/dsh-canary/serve.mjs) supplies `mode`, workspace/session IDs, existingMessage, createdSessionTitle, exact prompt and expectedAssistantText. The parent copies only required expectations into the same version-1 envelope and inserts its private invitation object without printing it. Set `mode: "isolated-dsh-canary"`, `workspaceId: "canary"`; expected output is `CANARY_SERVE_OK — deterministic real DSH; no external model.` Session identities/titles must come from that particular ready metadata, never demo defaults.

Prompt selection is hardcoded by mode: fixture retains `DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1`; actual DSH mode admits exactly `DSH_MOBILE_CANARY_ANDROID: synthetic emulator prompt.` An optional envelope `prompt` must exactly match the mode literal or configuration is rejected; no arbitrary prompt is accepted. The script keeps its historical `-FixtureConfigPath` parameter and private cache filename for compatibility, but both carry an explicitly validated mode.

Only debug HTTP on exact `127.0.0.1` / `localhost`, without URL userinfo/query/fragment/non-root path, is accepted. The script transfers UTF-8 JSON on stdin to `run-as dev.dshmobile.app`, into private `cache/acceptance-fixture.json`, with `umask 077`. No shared-storage invitation file is created. Instrumentation reads and deletes it immediately, before launch. Later stages receive the same expectations **without** the invitation. Script cleanup removes the same literal private cache path. The original local envelope/invitation is owned by the fixture operator and must be deleted/replaced when no longer needed.

Pairing failure diagnostics deliberately omit the cause and semantics tree: Compose errors can contain the complete invitation even when the field visually masks it. Neither raw instrumentation output nor logcat is persisted by the script. It reports safe stage names only. The script records APK and screenshot SHA-256, stage results and rollback errors in an ignored receipt, never secrets or config contents.

## Controlled commands

Run only after the parent authorizes build/device work. No replacement emulator/server is started by the script. `emulator-5580` must already be booted and disposable. It is hard-allowlisted, checked for `ro.kernel.qemu=1` and boot completion; no physical device or production host is permitted.

Parent joined build, from project root:

```powershell
./tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:testDebugUnitTest', ':app:lintDebug'
```

Parent fixture launch (only after TypeScript build; manage it as a background job):

```powershell
node host/dist/demo.js --state-dir artifacts/local-fixture/state --port 0 --invitation-file artifacts/local-fixture/invitation.json --answer-delay-ms 2000
```

The host owns selection of the local ephemeral port; the current synthetic existing session ID is `demo-session`. Create the envelope without printing the invitation, then:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-acceptance.ps1 `
  -FixtureConfigPath artifacts/local-fixture/acceptance-config.json `
  -AppApk android/app/build/outputs/apk/debug/app-debug.apk `
  -TestApk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk `
  -AdbPath "$env:ANDROID_HOME/platform-tools/adb.exe" `
  -Serial emulator-5580 -AllowDeviceChanges
```

The explicit `-AllowDeviceChanges` flag is an operator confirmation, not permission for a worker to ignore the parent go signal. The script never invokes Gradle. It installs the two APKs, clears **only** `dev.dshmobile.app` (including its old credentials), forwards only the fixture port, force-stops only that package for process-restoration evidence, changes system night mode/font scale and **only this app's** locales on this disposable emulator, and restores captured settings/per-app locales/removes its reverse in `finally`. Global device language is not changed. It refuses an already-owned fixture reverse port. No full emulator wipe, uninstall of unrelated apps, router/firewall changes or production token access occurs. The paired disposable app data is left for inspection; the operator can separately run `adb -s emulator-5580 shell pm clear dev.dshmobile.app` afterwards.

Native stage command shape (non-secret arguments only; private config must be transferred first):

```powershell
adb -s emulator-5580 shell am instrument -w -r `
  -e class dev.dshmobile.app.acceptance.MobileAcceptanceTest#fixtureJourney `
  -e acceptanceStage pairLifecycle `
  dev.dshmobile.app.test/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` can return exit 0 for test failures. The script additionally requires `OK (1 test)` and rejects failure/crash markers. It does not run the whole test method again to retry a consumed invitation. Get a new offer/new fixture state for another clean pairing run.

## Acceptance matrix

| Stage | Meaningful assertion/evidence |
| --- | --- |
| `pairLifecycle` | Empty invitation + disabled preview; harmless onboarding screenshot before input; real paste/preview/connect; `connection_state=ONLINE` after authenticated sync; existing fixture session selected by `session_{id}` and expected message visible; create via `workspace_{id}`; exact created title; one send of literal `DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1`; expected synthetic assistant output; exactly one visible canonical prompt and answer; unsent `DSH_MOBILE_UNSENT_DRAFT_V1`; Activity recreation then close/new Activity preserve draft and history. |
| `restored` | New instrumentation process after app-only force-stop restores encrypted pairing, selected conversation and unsent draft; online authoritative history contains one visible canonical prompt/answer. |
| `offline` | Fixture reverse removed before cold launch; offline state/reconnect action; no enabled send or create control. No fabricated persisted transcript/composer requirement. Recovered stage checks draft was not silently sent. |
| `recovered` | Reverse restored, explicit reconnect when present; online authoritative history restored without visible duplication; draft preserved and send enabled but not clicked. |
| `capture` | Native chat dark and light/font 1.3 screenshots; assert actual Activity configuration before saving. Required `chat-light-en` and `chat-light-ru` use checked per-app locales, never hardcoded translated selectors. |

`chat_timeline` must be the scrollable `LazyColumn`, not merely an outer `Box`, so `performScrollToNode` exposes the canonical prompt and answer before counting. Exact tags are documented in the UI contract and supplied by the UI worker. `connection_state` must expose `stateDescription = ConnectionState.name`. Screenshots are only captured with empty onboarding or after pairing UI disappears. Only synthetic chats are allowed in this fixture run.

## Evidence and limitations

Images come directly from `Instrumentation.uiAutomation.takeScreenshot()` (Android native compositor) into app external files, then `adb pull` to a fresh ignored `artifacts/android-acceptance/<timestamp>/` directory. Each image has JSON provenance: native API, pixel dimensions, locale, fontScale, uiMode and explicit mode labels. Screenshot/stage/aggregate receipts identify `fixtureOnly` versus `isolatedDshCanary`; `productionCanary` and `externalModel` are always false. The script checks matching image provenance against the chosen envelope mode. An aggregate receipt includes stage list, APK hashes and PNG hashes. The post-run screenshot batch must be visually inspected by parent/reviewer; passing tags alone does not prove contrast, keyboard/insets or unclipped typography.

Visible uniqueness is not proof of exactly-once upstream execution or the entire bounded history: `LazyColumn` only materializes nearby items. Fixture-side receipt/audit verification should independently confirm one create, one send, literal synthetic prompt, request identity and new authoritative snapshot reads. No such receipt endpoint is invented by this suite. Stop/cancellation, uncertain/rejected delivery, revocation, TLS/pins, background notifications and camera input are not covered by this initial ordered journey and must not be claimed as passing.

Offline stale-history behavior requires care: an in-process network loss can retain a snapshot, but a cold offline process is not required to persist transcripts. The test must not fabricate cached history expectations where the repository contract persists only selected-session/draft/pending. If implementation cannot expose the expected offline state or draft in the cold stage, report it and coordinate the narrow correct lifecycle stage rather than inserting a mock repository.

English/Russian resource completeness and visual review are separate checks. The script requires both native locale captures and saves/restores original app locales in `finally`; instrumentation asserts the actual Activity locale. Use supported `get-app-locales`/`set-app-locales` on a disposable API 36 emulator, not global language changes or guessed broadcasts. Keep run-specific receipts private; these instructions do not claim a performed acceptance run.
