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

## Tag CI and locally signed GitHub draft

The [draft-release workflow](../.github/workflows/release.yml) runs only for pushed `v*` tags. The ordinary [CI](../.github/workflows/ci.yml) stays read-only and unchanged. Android CI builds debug/test APKs, runs JVM tests, `lintDebug` and full `lintRelease`, then builds the minified **unsigned** release APK and checks its actual `aapt2` permission surface/no Google scanner-metrics components. A separate Node 24 Windows job installs locked host dependencies, builds/tests them and calls [the host packager](../tools/package-host.ps1) to produce its ZIP. Each asset has a SHA-256 sidecar. Only after both jobs pass does the workflow create a **new draft** GitHub Release using the built-in `GITHUB_TOKEN` (`contents: write` only for that final job); no custom secret, Android key or DPAPI data is required. It fails if any release already exists for the tag, whether draft or published. It never uploads to/edits an existing release and never uses `--clobber`. If another release appears between lookup and creation, the create request fails instead of falling back to upload. A rerun cannot refresh a prior draft: inspect it manually and use a new version/tag for a new release; no automatic deletion or replacement occurs.

Android CI asset names include the workflow run ID/attempt and the clearly named `UNSIGNED-verification.apk` is verification evidence, **not an installable or owner-signed download**. CI also retains its own R8 mapping; it is not interchangeable with the owner's locally built mapping. The stable owner's key remains local and must **never** be uploaded as an Actions secret, artifact, log, Gradle property or release asset.

Owner steps after reviewing the draft (commands are instructions, not a performed publication):

1. Check out the **exact tagged commit** in a clean local checkout and confirm Android `versionName`/`versionCode` match the intended release. Do not sign a different uncommitted tree or regenerate a missing key. The existing key must already have the tested backup described below.
2. Build, align, sign and verify locally with the existing helper (reuse the key; omit `-InitializeKey`):

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tools/android-release.ps1
   ```

3. Use the helper's newly printed output directory, not an arbitrary older artifact. Verify its receipt and APK hash, keep its matching R8 mapping privately, and perform the approved native/physical-device acceptance before publishing. Create a checksum and upload **only** the signed APK and checksum to the existing draft:

   ```powershell
   $tag = 'v0.4.0'
   $releaseDir = '<new output directory printed by android-release.ps1>'
   $receipt = Get-Content -LiteralPath (Join-Path $releaseDir 'release-receipt.json') -Raw | ConvertFrom-Json
   if ($tag -cne ('v' + $receipt.versionName) -or $receipt.versionCode -ne 4) { throw 'Wrong release version' }
   $apk = Join-Path $releaseDir $receipt.apkName
   $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
   if ($hash -cne $receipt.apkSha256.ToLowerInvariant()) { throw 'APK hash mismatch' }
   $checksum = $apk + '.sha256'
   Set-Content -LiteralPath $checksum -Value ($hash + '  ' + [IO.Path]::GetFileName($apk)) -Encoding ascii
   $draft = gh release view $tag --json isDraft --jq '.isDraft'
   if ($LASTEXITCODE -ne 0 -or $draft -ne 'true') { throw 'Expected an existing draft release' }
   gh release upload $tag $apk $checksum
   if ($LASTEXITCODE -ne 0) { throw 'Upload failed; inspect the draft before retrying' }
   ```

   GitHub CLI uses the owner's local authenticated account; this upload is deliberately manual. Do not use `--clobber` to replace a signed asset without investigating why it differs. Review the draft notes, host ZIP, unsigned verification label and signed-APK checksum, then publish explicitly in GitHub when acceptance is complete. Do not upload signing state or the private receipt/mapping by a directory wildcard. See official [GitHub CLI upload](https://cli.github.com/manual/gh_release_upload) and [draft creation](https://cli.github.com/manual/gh_release_create) guidance.

The app's local QR scanner uses [CameraX 1.6.2](https://developer.android.com/jetpack/androidx/releases/camera#1.6.2) and [ZXing core 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4) (Apache-2.0); it has no network/Google services/metrics dependency. `CAMERA` is declared optional and requested only for a scan after a rationale. The release signing guard permits exactly `INTERNET`, `CAMERA` and the existing app-signature receiver permission, never `ACCESS_NETWORK_STATE` or background camera services. CameraX's `camera-view` transitively brings `camera-video` → Media3, whose manifest requests network state for a network observer; that permission is explicitly removed because the app uses only `Preview`/`ImageAnalysis`, not video or a network observer. The release R8 usage report must keep showing the unused observer removed. Import/paste and subsequent HTTPS pairing do not need camera access. AGP defaults plus dependency consumer rules are used; no broad R8 keep rules are added. Real minified camera/permission/torch behavior is a native acceptance gate, not proven by a JVM test or successful shrinking.

### Local regression checks and scanner rotation

The create-only draft step has a behavioral test using an isolated fake `gh` command (no authenticated GitHub call); the Android verification job runs it before building, and it can also run locally. From the project root, use Python 3 and Bash; on Windows Git Bash can be selected explicitly:

```powershell
$env:ANDROID_TEST_BASH = (Join-Path $env:ProgramFiles 'Git/bin/bash.exe')
python.exe ./.github/tests/release_workflow_test.py
```

It exercises existing draft/published releases, owner publication between check/upload, a release appearing between lookup/create, failed lookup and first draft creation. CI must never fall back to `release upload` or `--clobber`.

Manual native check on an **approved disposable device**, not part of a local build-only gate:

1. Open the scanner and rotate portrait → landscape → portrait before decode. The scanner route remains open, a new CameraX session binds to the new lifecycle owner, and preview/flash state resets. Back stops the camera and returns to pairing.
2. Rotate as a synthetic QR becomes readable. A result admitted before lifecycle pause may open review, but rotation discards that memory-only review and requires an explicit new review; it must not pair. A queued result for an already paused/stopped/disposed owner is discarded, the saved scan route remains open, and the new session must re-scan. No payload/review may enter SavedState or be restored from it.
3. Scan a valid invitation after rotation: one trust dialog, matching endpoint/pin, no duplicate confirmation or automatic POST. Confirm only the exact shown invitation. Also try a delayed file read followed by scan/paste: late file content must never replace the new input/trust selection.

The JVM delivery-gate test covers stopped/disposed owner races and one-result admission, not a real camera/Activity recreation. The manual checks above remain required native acceptance.

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
