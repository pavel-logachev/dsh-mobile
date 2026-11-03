# DSH Mobile 0.9.2 — release notes and acceptance record

0.9.2 fixes discarded phone-task watches and image uploads, including large uploads over the relay. Upgrade the app and host plugin together. Self-hosted relay operators must also rebuild and update the relay image: the large-upload fix spans the relay and the plugin's connector.

## Versions

| Component | Release identity |
|---|---|
| Android | `0.9.2`, `versionCode 19` |
| Host plugin | `0.9.2` |
| Relay deployment transaction | schema 2; operator tooling, not an app or host component |

## Changes

| Commit | Side | Change |
|---|---|---|
| `cf784c7` | host | Settle discarded phone prompt watches as `rejected`; consumptionVersion 2 re-scans version 1 checkpoints. This repairs watches left waiting after the corresponding queued prompts were removed. |
| `b62b492` | Android | Give streaming uploads a dedicated bounded 120-second call deadline instead of the ordinary 15-second budget. Add the bounded `DshMobileUpload` logcat diagnostic. |
| `2289ea9` | Android | Convert picked HEIF/HEIC images locally to JPEG on Android API 28+. Decode with sampling targeting at most 4096 pixels per side and 16 MiB bitmap memory; reject allocations over 16 MiB. Encode sRGB JPEG at quality 90; the ordinary 20 MiB attachment limit still applies. |
| `025c516` | relay, host connector | Admit short TLS tails during large uploads. Previously the small-message limiter closed the stream at the 65th small TLS tail, cutting uploads of about 5 MB and larger over the relay. |
| `48fc5b0` | Android, host | Bump app and plugin to 0.9.2, Android versionCode 19. |
| `c59db09`, `df02a37`, `d82a47c`, `0f78bd4` | operator tooling, docs | Guarded relay image-update transaction (schema 2) and [RELAY_DEPLOYMENT](RELAY_DEPLOYMENT.md). These commits do not change the app, host or relay runtime source. |
| `367bae5` | tests only | Bind the cancellation CI fixture and its client to the same explicit IPv4 loopback address, avoiding a dual-stack `localhost` route mismatch after cancellation. Product code unchanged. |

## Artifacts and provenance

The signed APK is the exact APK tested on the owner's phone, built from `48fc5b0`. Later commits through `0f78bd4` change only relay deployment tooling and documentation; `367bae5` changes only the cancellation test fixture. The original full-directory check below therefore lists that test-only change, rather than being empty. Verify runtime-source equivalence separately:

```text
git diff --stat 48fc5b0 HEAD -- android host relay
# expected: PhoneTransportTest.kt only (6 insertions, 1 deletion)
git diff --stat 48fc5b0 HEAD -- android/app/src/main host relay
# expected: empty
```

The test-only correction passed GitHub CI run [41666680186](https://github.com/pavel-logachev/dsh-mobile/actions/runs/41666680186) on `367bae5` (Android, Linux host and Windows host). CI must also pass on the final documentation commit before main is updated.

| Artifact | SHA-256 |
|---|---|
| `dsh-mobile-0.9.2.apk` | `fbe10caaaa2b682bd5e1f35a482fb052f407aaf50482fa2a58a4e182b5c15e47` |
| `dsh-mobile-host-0.9.2.zip` | `ae0c6b2bc861a5b4dd25ee690b3ba83da4d2896d331728a642576dd42e59f688` |

APK signing certificate SHA-256, unchanged from earlier releases: `F9080C63B49A0BDE6B74AA2ADF7E9531D6C4F305978A24D3F5D271A39F1B7B95`.

The host ZIP was built reproducibly: a repeat build produced identical bytes. Its exact contents are installed in the owner's DSH. The tag workflow (run 41671180075) independently built and verified an unsigned APK and a host ZIP. Its ZIP (`80992b60…cd64`) has the same 86 entries; 84 are byte-identical and the other two, `package.json` and `dsh-mobile-host-release.json`, differ only in serialization: `package.json` parses to identical values (whitespace and escaping differ), and the manifest differs only in the recorded SHA-256 of that `package.json`. The release carries the tested ZIP and the tested signed APK with their `.sha256` sidecars; the unsigned CI APK is not a release download. See [ANDROID_RELEASE](ANDROID_RELEASE.md).

CI on the tagged commit `c11ba12`: runs 41671171742 (main) and 41671180092 (tag) green on Linux host, Windows host and Android. The four release assets were downloaded back from GitHub and match the tested files byte for byte; the APK certificate and version 0.9.2 / 19 were re-checked on the download.

## Upgrade

- Update the app and plugin together. Old host/new relay and new host/old relay combinations have not been verified.
- For plugin installation and rollback, follow [SETUP](SETUP.md#10-upgrade-rollback-revoke-and-uninstall) with 0.9.2 artifacts.
- The signed APK was installed with `install -r` over 0.9.1 on the owner's phone; pairing was retained.
- Self-hosted relay operators must rebuild and update their relay image as well as the plugin. Follow the new [guarded relay update procedure](RELAY_DEPLOYMENT.md).

## Known limitations

- Direct LAN upload has not been verified on the phone for this release.
- Mixed old/new host and relay versions have not been verified.
- Carried-over limitations from [0.9.1](RELEASE_0.9.1.md#known-limitations) remain: failure alert on a physical phone and exact Doze alert delay are unverified. No new claim is made about those scenarios.
- HEIF conversion requires Android API 28+; this is not a claim of HEIF support on Android 8.

## Verification

Recorded 2026-11-03 (MSK).

| Gate | Result |
|---|---|
| Host RC checks on `48fc5b0` | `npm.cmd run check`: 374/374 with the installed Cordis module. |
| Relay RC check | 19/19. |
| Android RC checks on `48fc5b0` | `testDebugUnitTest`: 499/499; `lintRelease`: 0 errors, 40 warnings. |
| Relay deployment tooling | POSIX suite on Linux with fake Docker: 41/41 on `0f78bd4`. Independent review of the transaction up to `d82a47c`: FAIL twice, PASS after fixes. `0f78bd4` (normalizing how a newer Docker Engine renders two unset defaults, `Dns` and `OomKillDisable`) was not independently reviewed; it rests on a diagnostic comparison on the production Engine and the suite above, and the guarded production update with it committed. This is tooling evidence, not a live Docker protocol test. |
| Plugin 0.9.2 on the owner's DSH | Activated through the change pipeline at 16:45 MSK: batch committed, 0 errors, package post-check 3/3, smoke passed. |
| Owner's production relay | Updated to the fixed image at 15:20 MSK using the guarded transaction; host reconnected in about 21 seconds, update committed. |
| Phone identity and window | OnePlus CPH2747 / Android 16, 16:55–17:20 MSK. |
| Upgrade on the phone | Exact signed APK 0.9.2 installed over 0.9.1 with `install -r`; pairing retained. |
| Stuck phone-task watches | Two prompts removed from the 0.9.1 queue are now `rejected` in host state; no app notification remained over 5+ minutes. |
| PNG upload over relay | 21,866 B, HTTP 201 in 7.4 s, agent replied about its content. |
| HEIC converted to JPEG over relay | 231,274 B JPEG, HTTP 201 in 7.5 s, agent replied about its content. |
| Large JPEG over relay | 5,155,222 B, HTTP 201 in 7.8 s, agent replied about its content. |
| Fresh camera photo over relay | 2,669,234 B, HTTP 201 in 8.0 s, agent replied about its content. |
