# Phase 1 notification acceptance

Branch: `feat/notifications`; baseline: `2c6ac63f29c7bbaa9ae881b10a1f90528df5014a`.

## Commands and boundary

Run from the assigned worktree using existing locked dependencies and build wrapper; no dependency/toolchain changes. No production runtime/profile/session modification, host release installation, physical-phone deployment, push or delegation.

```powershell
# host/
npm.cmd run check
$env:DSH_MOBILE_CORDIS_MODULE = '<installed @deepseek-ai/cordis/lib/index.js>'
npm.cmd run check
# worktree root
./tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', ':app:assembleRelease', ':app:lintRelease'
aapt2 dump permissions android/app/build/outputs/apk/debug/app-debug.apk
aapt2 dump permissions android/app/build/outputs/apk/release/app-release-unsigned.apk
git diff --check
```

The optional Cordis module is the inspected installed 4.0.4 module, read-only. Its tests compose isolated contexts/controller stubs and temporary TLS/SQLite/relay fixtures. They are not live user-session acceptance.

## Final results

- Host without optional Cordis: 104 tests, 100 pass, 4 intentional skips, 0 failures; build/check exit 0.
- Host with installed Cordis: 104/104 pass, no skips/failures; build/check exit 0.
- Android six wrapper tasks: BUILD SUCCESSFUL, exit 0; JVM XML totals 175 tests, zero failures/errors.
- `lintDebug` and `lintRelease`: no errors; warnings remain (19 debug/21 release). These do not certify OS background delivery.
- Both APK permission audits and `git diff --check`: pass.

## Checked behavior

- Host producer completion requires exact completed terminal sequence/turn and visible committed assistant text; cancels/missing terminal/intermediate stream do not alert. A later queued waiting turn cannot erase the preceding completed-turn notification.
- Cold opening proofs close without requesting the activation frame. Actual Cordis global bridge observes a newly created synthetic PC session with no phone chat observation; no create/prompt/cancel mutation is invoked.
- Coalesced attention episodes clear with the same identifier; startup/restart does not create completion storms; missing/pruned cursors reset rather than get stuck in `hasMore`; foreign/tampered cursors are rejected; current grants/mapping/policy filter; ambiguous evidence degrades without pretending completion.
- Settings optimistic revisions, bounded overrides, device stream caps, bearer-only routes and public HTTP/SSE serialization exercised. Producer overflow recovers current pending silently; regular live fan-out and producer watermark commit together.
- Android policy/reset/dedupe/expiration/names/retry JVM tests, plus MockWebServer notification SSE cursor/auth/Retry-After and oversized-frame tests. Needed and cleared within one page do not emit an obsolete audible attention notification. Cursor/dedupe persist before platform display (receipt-before-display, not exactly-once).
- Debug and release compile/R8/lint, JVM tests, and existing Android instrumentation APK compilation. No new instrumentation suite is claimed executed.

## Permission audit

Both APK variants contain existing INTERNET/CAMERA and AndroidX own-signature dynamic receiver permission. The only new platform permissions are POST_NOTIFICATIONS, FOREGROUND_SERVICE and FOREGROUND_SERVICE_SPECIAL_USE. No RECEIVE_BOOT_COMPLETED, wake lock or battery-exemption request permission. Manifest declares a non-exported specialUse service and rationale; target/min SDK remain unchanged.

## Self-review corrections

Review of the changed host/Android/domain/UI scope found and corrected stale journal overwrite across async listing, unbounded producer promise scheduling, ambiguous cold proof, non-atomic live watermark/fan-out, post-decode-only SSE limits, queued-turn suppression, stale same-page attention display, pruned-cursor replay loop, offline inability to disable the master toggle, and unbounded Stop preference sync. Optional Cordis assertions were updated to account for the intentional extra baseline cold read/list, then strengthened with a new PC-session event bridge scenario. No external reviewer/subagent was used.

## Not accepted on hardware

Emulator smoke deliberately skipped: Pora_API_36 had an existing owner process, and later inspection found an already running read-only AVD on port 5580. It was not reclaimed, installed into or stopped. No physical phone or live DSH session was used. Lock-screen redaction/tap, actual FGS Stop, API26/33/34 behavior, Doze, process death, relay background reconnection and OnePlus overnight battery/delivery remain manual acceptance gates. specialUse distribution approval remains a separate gate.

Implementation limits are recorded in [Phase 1 as built](NOTIFICATIONS_DESIGN.md): best-effort foreground service, no boot resurrection/UnifiedPush, shared per-kind mute, inline pending cap, serial startup proof and lazy retention. Gap/downtime completion loss is surfaced as limited coverage; platform display can be lost after a persisted receipt.
