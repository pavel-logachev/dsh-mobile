# Build and verification

Status: development preview. These are reproducible commands, not a record of a completed acceptance run. See [the acceptance plan](PLAN.md) for fixture, real DSH, emulator and physical-device gates. No physical-phone or mobile-route acceptance has been completed.

## Prerequisites

- Host: Node.js **24.x**, npm, and the committed lockfile. The host uses Node's built-in SQLite; no separate database service is required. For official Node/OpenSSL sources, prerequisite consent, a new-shell version check and protecting DSH's own Node launcher, follow [the recipient prerequisite procedure](SETUP.md#prerequisite-download-and-installation--separate-consent).
- Android: **JDK 21** recommended (JDK 17 is also supported by the Windows helper); SDK platform **android-36**, build-tools **36.0.0**, and platform-tools for optional device work.
- Dependencies are downloaded from configured public repositories. Keep caches, local SDK/JDK paths, credentials and generated output outside tracked files.

The pinned foundation uses AGP 9.1.1, Gradle 9.3.1 and AGP's built-in Kotlin 2.2.10. Compose and serialization plugins match Kotlin. Do not add `org.jetbrains.kotlin.android` alongside built-in Kotlin. See [the version catalog](../android/gradle/libs.versions.toml) for the complete dependency set and [the app build](../android/app/build.gradle.kts) for SDK settings.

## Host

From the repository root:

```sh
cd host
npm ci
npm run build
npm test
```

`npm run check` combines build and test. Current scripts are defined in [the host package](../host/package.json). Tests must use synthetic fixtures or disposable state, never private chats or production credentials. Passing them does not prove that the companion plugin has been loaded into a real DSH installation. Runtime/plugin setup must follow the [architecture boundary](ARCHITECTURE.md), not auto-modify an active installation. The adapter/plugin requires an explicit owner `dshVersion` declaration from the exact allowlist `0.2.0-rc.2`, `0.2.1-alpha.1`; every other declaration is rejected (no semver range). Both versions' declarations and compiled JS were inspected and normal/registry isolated real-runtime canaries passed. The installer checks `dsh --version`; standalone `setup-direct` defaults to `--dsh-version auto` and validates the actual command output, while the plugin does not infer a version. An owner declaration is still not proof that the live profile mounted correctly. Do not invent DSH startup flags from the companion package's script names.

For explicit companion installation and rollback instructions, see [DSH integration](DSH_INTEGRATION.md) and its [disabled Cordis patch example](../examples/cordis.patch.yml). These are operator-reviewed steps, not an automatic deployment performed by the build.

### Portable Windows host release

Use Node 24 plus the committed lockfile to build. QR (`qrcode` core/renderer) and the bounded inert YAML parser are bundled by [build-host.mjs](../tools/build-host.mjs); `ws` is the only external runtime dependency, and the zip includes it. Development-only independent QR decoding uses `jsqr`. Package with the [reproducible helper](../tools/package-host.ps1):

```powershell
# Output outside Git; use an empty/new directory (existing archives are not overwritten).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/package-host.ps1 `
  -OutputDirectory '<ABSOLUTE_RELEASE_OUTPUT_DIRECTORY>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/install-host.test.ps1 `
  -CacheDirectory '<ABSOLUTE_DISPOSABLE_TEST_CACHE>'
```

The helper emits `dsh-mobile-host-<host-package-version>.zip` and `.zip.sha256`. Entry order is sorted and timestamps fixed; two builds from the same source/dependencies were byte-identical. Payload: built JS/declarations, package metadata, LICENSE and bundled-dependency licenses, pinned `ws`, reviewed Windows installer and per-file hash inventory. There is no recipient-side npm install, source checkout, TypeScript, TLS private state or Android SDK. Inventory hashes validate extraction; the release SHA-256 must be verified before executing the installer.

The [guarded installer](../tools/install-host.ps1) targets `%LOCALAPPDATA%/DSHMobile/plugin/<version>` immutably, preflights Node/DSH/profile, and creates stable registry direct TLS config only if absent. It prints its marked additive patch without activating by default. Profile mutation requires `-Activate` (including `-Upgrade`, `-Rollback`, `-Uninstall` actions), backup and HMR/listener checks; it never restarts DSH or changes firewall/OS trust. The [recipient agent runbook](SETUP.md) contains numbered consent gates, LAN/Tailscale instructions, QR and rollback.

Synthetic installer tests override LOCALAPPDATA and DSH_HOME, use a fake `dsh --version`, a unique test port and a synthetic TCP watcher. They prove file/ACL/profile/upgrade logic and reproducibility, not production HMR acceptance. The inert YAML parser detects unmarked companion IDs semantically, including flow style, quoted keys and aliases, while comments/code-tag scalar contents are not entries. Source setup uses vetted OpenSSL 3 to generate P-256/SHA256 with CA:false/serverAuth/digitalSignature and exact SANs; Node's X509Certificate is read/verify-only.

Pairing requires explicit output consent: `pair` needs `--qr` and/or a new private `--output`; `remote-pair` requires `--output` and optionally `--qr`. Both reject missing output before creating an offer/grant. Invitation JSON is never printed to stdout. QR/private files are one-use credentials, not diagnostics; automation reads the protected file. The built-dist CLI regression exercises a long valid certificate, proves successful `--qr --output` capacity fallback and confirms the saved remote grant is not revoked. The QR bundle shares the external built `errors.js` identity with CLI rather than bundling another `HostError` class.

```powershell
node --test tools/onboarding-docs.test.mjs tools/dsh-canary/*.test.mjs
```

## Android on Windows

From the repository root, using Windows PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-build.ps1
```

This process-scoped execution-policy option does not change system policy. The helper defaults to `:app:assembleDebug` and `:app:testDebugUnitTest`. It discovers SDK and a supported JDK, prefers an explicit argument when supplied, sets environment variables only for the build process and restores them afterward. It does not create a machine-specific `local.properties`.

To select installations explicitly:

```powershell
./tools/android-build.ps1 -SdkPath $env:ANDROID_HOME -JdkPath $env:JAVA_HOME
```

Run the complete local Android gate in a PowerShell session permitted to run the helper:

```powershell
./tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleRelease'
```

Use a supported JDK 21/17 rather than assuming any newer global `JAVA_HOME` is compatible. See [the helper](../tools/android-build.ps1) for discovery and failure checks. A `NO-SOURCE` unit-test task is not proof that behavior tests passed.

## Android on Linux

Set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to your Android SDK. With official SDK command-line tools installed:

```sh
sdkmanager --install 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
cd android
chmod +x gradlew
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon --console=plain :app:assembleRelease
```

Accept SDK licenses according to the Android SDK terms before building. No local paths belong in version control. Android Studio may create an ignored local SDK properties file for interactive development.

## Outputs and integrity

- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Release APK: `android/app/build/outputs/apk/release/app-release-unsigned.apk` with the current unsigned release configuration.
- Unit-test and lint reports: under `android/app/build/reports/`.

The debug APK is for development. Release shrinking is enabled; the ordinary Gradle release output remains unsigned and cannot be installed as-is. For a properly signed private APK, use the guarded [private-release helper and backup procedure](ANDROID_RELEASE.md). It keeps an app-specific durable key outside Git and signs a separate APK after alignment; no keys, passwords or Play publishing configuration are tracked. Private signing does not guarantee Play Protect acceptance and does not authorize public publication.

The official Gradle wrapper was generated from an official Gradle distribution. [Wrapper properties](../android/gradle/wrapper/gradle-wrapper.properties) pin distribution SHA-256:

```text
b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06
```

The generated wrapper JAR's verified SHA-256 is:

```text
b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13
```

Compare updates to the [official distribution checksum](https://downloads.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256), [official wrapper checksum](https://downloads.gradle.org/distributions/gradle-9.3.1-wrapper.jar.sha256) and [Gradle wrapper documentation](https://docs.gradle.org/current/userguide/gradle_wrapper.html). Distribution verification occurs on a fresh download; an already-cached distribution is not revalidated by that property alone.

## CI and acceptance limits

Conversation history is bounded to the current snapshot. If the bounded window omits message-replacement endpoints, the adapter fails closed rather than returning an unverified history. Some compacted conversations therefore require the desktop until history paging is implemented. A successful short-conversation canary does not establish complete history support.

[CI](../.github/workflows/ci.yml) defines separate Node 24 host and JDK 21 Android jobs, validates the wrapper and installs the required SDK packages. Actions are pinned to full commits verified against official repository refs. CI is configured, not claimed to have run on GitHub. It neither deploys a plugin nor boots an emulator or publishes artifacts.

Debug cleartext is limited to exact loopback hosts `127.0.0.1` and `localhost`. A local fixture may be reached with `adb reverse tcp:9443 tcp:9443`; the fixture must be explicitly labelled as not connected to DSH. Never reverse or expose the raw DSH Web port as a substitute for the companion API. Release requires HTTPS with hostname/certificate checks and an invitation SPKI pin.

Do not change router, firewall, VPN, public ingress or an active DSH profile during these checks. Run a real plugin canary and device/network checks only with the owner's explicit approval. Keep generated reports and diagnostics out of Git and scrub all shared diagnostics as described in [Security](SECURITY.md).
