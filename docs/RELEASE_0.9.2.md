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

## Artifacts and provenance

The signed APK is the exact APK tested on the owner's phone, built from `48fc5b0`. Later commits through `0f78bd4` change only relay deployment tooling and documentation. The release documentation commit must not change `android`, `host` or `relay`; verify at the release head:

```text
git diff --stat 48fc5b0 HEAD -- android host relay
# expected: empty
```

| Artifact | SHA-256 |
|---|---|
| `dsh-mobile-0.9.2.apk` | `fbe10caaaa2b682bd5e1f35a482fb052f407aaf50482fa2a58a4e182b5c15e47` |
| `dsh-mobile-host-0.9.2.zip` | `ae0c6b2bc861a5b4dd25ee690b3ba83da4d2896d331728a642576dd42e59f688` |

APK signing certificate SHA-256, unchanged from earlier releases: `F9080C63B49A0BDE6B74AA2ADF7E9531D6C4F305978A24D3F5D271A39F1B7B95`.

The host ZIP was built reproducibly: a repeat build produced identical bytes. Its exact contents are installed in the owner's DSH. The tag workflow independently builds and verifies an unsigned APK and host ZIP, then creates a draft. Retain the CI host ZIP only if its hash matches the table; otherwise replace it and its checksum with the tested ZIP, recording the CI hash and an entry-content comparison in the release operator's report. Replace CI-only assets with the tested signed APK and its checksum. The final draft must contain exactly the two artifacts above and their `.sha256` sidecars; unsigned verification APKs are not installable release downloads. See [ANDROID_RELEASE](ANDROID_RELEASE.md).

GitHub CI run IDs, final release-head identity, downloaded-asset verification and draft URL are recorded in the release operator's report after the documentation commit; this record does not pre-claim those later checks.

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

Recorded 2026-11-03 (MSK). The following RC/device/production results were supplied as verified release evidence; the release operator does not repeat device or production actions.

| Gate | Result |
|---|---|
| Host RC checks on `48fc5b0` | `npm.cmd run check`: 374/374 with the installed Cordis module. |
| Relay RC check | 19/19. |
| Android RC checks on `48fc5b0` | `testDebugUnitTest`: 499/499; `lintRelease`: 0 errors, 40 warnings. |
| Relay deployment tooling | POSIX suite on Linux with fake Docker: 41/41; two independent reviews, final PASS. This is tooling evidence, not a live Docker protocol test. |
| Plugin 0.9.2 on the owner's DSH | Activated through the change pipeline at 16:45 MSK: batch committed, 0 errors, package post-check 3/3, smoke passed. |
| Owner's production relay | Updated to the fixed image at 15:20 MSK using the guarded transaction; host reconnected in about 21 seconds, update committed. |
| Phone identity and window | OnePlus CPH2747 / Android 16, 16:55–17:20 MSK. |
| Upgrade on the phone | Exact signed APK 0.9.2 installed over 0.9.1 with `install -r`; pairing retained. |
| Stuck phone-task watches | Two prompts removed from the 0.9.1 queue are now `rejected` in host state; no app notification remained over 5+ minutes. |
| PNG upload over relay | 21,866 B, HTTP 201 in 7.4 s, agent replied about its content. |
| HEIC converted to JPEG over relay | 231,274 B JPEG, HTTP 201 in 7.5 s, agent replied about its content. |
| Large JPEG over relay | 5,155,222 B, HTTP 201 in 7.8 s, agent replied about its content. |
| Fresh camera photo over relay | 2,669,234 B, HTTP 201 in 8.0 s, agent replied about its content. |

Release publication is a separate owner/lead action after review. The release operator must leave v0.9.2 as a draft and must not mark it latest.
