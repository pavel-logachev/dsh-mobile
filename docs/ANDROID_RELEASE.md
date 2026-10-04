# Private Android release APK

This is private APK distribution, not Google Play publication. A stable release signature is required for a proper install/update identity; it does **not** establish a trusted/verified developer or guarantee that Play Protect accepts the APK. Do not disable Play Protect, reuse another app's private key, or change the package name to evade a warning.

## One-time key and signing

From the project root on Windows PowerShell 5.1 with the prerequisites in [BUILD.md](BUILD.md):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-release.ps1 -InitializeKey
```

If discovery fails, supply the existing SDK/JDK with `-SdkPath` and `-JdkPath`. This is a process-scoped execution-policy option, not a persistent Windows policy change.

The [helper](../tools/android-release.ps1):

- Creates a **DSH Mobile-specific**, password-protected PKCS12 keystore, alias `dsh-mobile-release`, RSA 3072, SHA256withRSA, 10,000-day validity. The certificate is self-signed; its CN is a label, not verified developer identity.
- Stores durable state at `%LOCALAPPDATA%/DSHMobile/signing`, outside Git and outside disposable DSH/build caches. The directory and each state file have a protected current-user-only ACL. Administrators/host compromise are not excluded by this protection.
- Stores its random password with Windows DPAPI **CurrentUser**, not in source, logs, command-line arguments, Gradle properties or a plaintext local password file. Only the signing/keytool child receives a password environment variable; the parent/global environment is untouched.
- Refuses existing, partial, unexpected, redirected or non-owner-only state instead of silently replacing keys. If initialization is interrupted, preserve the partial files and investigate; never rerun by deleting them without determining whether a release already used that key.
- Builds the existing minified/resource-shrunk unsigned release; runs debug unit tests and **full `lintRelease`**. It does not add an AGP signingConfig, change the app/version/permissions, or give signing secrets to Gradle. AGP 9 built-in Kotlin remains unchanged.
- Checks the APK package/SDK/permissions, rejects debuggable/debug-network-config output, and requires cleartext off. It runs `zipalign -P 16` **before** signing, then verifies alignment and APK v2/v3 signatures against the pinned certificate. v1 is unnecessary for minSdk 26; v4 is not needed for normal APK sideloading.
- Creates a new ignored directory under `artifacts/deliverables/private-release/` containing the signed APK, its R8 mapping and a nonsensitive `release-receipt.json` (APK/certificate/mapping SHA-256, scheme and build-check results). Preserve this mapping with the corresponding release for crash retracing.

Future signing runs must reuse the existing state:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-release.ps1
```

Read-only local identity/ACL check (no build/device):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-release.ps1 -VerifyKeyOnly
```

The CLI [guard tests](../tools/android-release.test.ps1) use synthetic private directories under ignored artifacts; they do not create a key, build or touch any device:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-release.test.ps1
```

## Mandatory recoverable backup

**DPAPI alone is not a portable backup.** It is tied to the Windows user/profile and its protected keys. Copying `password.dpapi.txt` to a different computer/profile does not guarantee recovery. Losing the keystore **or** its recoverable password means losing the existing update identity; generating a new key does not restore it.

Before relying on the release identity, the owner must make and test an off-machine encrypted backup of:

1. `release.p12` (private signing key).
2. A **recoverable signing password** in a trusted password manager/encrypted vault, independently of this Windows DPAPI profile. The store and key use the same generated password.
3. The public `certificate.der` and `identity.json`, so the restored certificate SHA-256 can be checked independently against a prior release receipt.

The owner can decrypt the local password via `ConvertTo-SecureString` under the original Windows account and transfer it directly into a secure vault **in a private local operator session**. Do not use `Write-Host`, shell history, screenshots, transcript logs, chat, Git or shared storage for the plaintext. No plaintext export or automatic backup is performed by the helper. A secure password/keystore backup and restore test remain an explicit owner operation, not a completed acceptance claim.

Do not replace the stored public certificate/pin to make a different key pass. After recovery, verify the original certificate and an APK signed with it before delivering another update.

## Install/update and acceptance limits

- The APK is a single universal APK, minSdk 26 (Android 8.0), targetSdk 36. Verify the actual signed artifact metadata and SHA-256, not its filename.
- A release signed by this new certificate cannot update the existing `CN=Android Debug` installation in place. Keep the tested debug APK unchanged. If a debug installation exists, an owner-approved uninstall/reinstall is necessary and loses its local pairing, draft and pending state; re-pair explicitly. Do not silently clear or uninstall an app.
- Future private updates retain package `dev.dshmobile.app` and the **same certificate**; increment versionCode for new deliveries. A larger versionCode cannot compensate for a different signing certificate.
- Release is non-debuggable and HTTPS-only with certificate/hostname verification and an invitation SPKI pin. Do not weaken it to reuse the debug HTTP fixture or `run-as` acceptance script.
- Signature/alignment/lint success is **not** native release acceptance. Separately install/launch the exact minified signed APK on an approved disposable device, check package flags and exercise pairing/TLS, native UI, serialization, Keystore persistence and reconnect. Existing debug E2E evidence is not release E2E evidence. Actual physical-phone Play Protect acceptance is a separate gate; if blocked, retain the exact message, device/Android version and transferred APK hash before concluding its cause.
- No Play account changes, public APK publication, global trust changes, router/VPN/firewall changes or production DSH mounting are part of signing.

Official guidance (Context7 consulted first): [app signing and key safety](https://developer.android.com/studio/publish/app-signing), [apksigner and password inputs](https://developer.android.com/tools/apksigner), [zipalign ordering](https://developer.android.com/tools/zipalign), [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin). The inspected project pins AGP 9.1.1; no toolchain upgrade is required for this signing correction.
