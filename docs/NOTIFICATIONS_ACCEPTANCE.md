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

- Host without optional Cordis: 121 tests, 117 pass, 4 intentional skips, 0 failures; build/check exit 0.
- Host with installed Cordis: 121/121 pass, no skips/failures; build/check exit 0.
- Android six wrapper tasks (previous remediation run; unchanged in host-only follow-up): BUILD SUCCESSFUL, exit 0; JVM XML totals 184 tests, zero failures/errors.
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

Review of the changed host/Android/domain/UI scope found and corrected stale journal overwrite across async listing, unbounded producer promise scheduling, ambiguous cold proof, non-atomic live watermark/fan-out, post-decode-only SSE limits, queued-turn suppression, stale same-page attention display, pruned-cursor replay loop, offline inability to disable the master toggle, and unbounded Stop preference sync. Original Cordis assertions accounted for eager baseline cold reads; remediation now asserts the lazy baseline performs no idle cold read/no zero-opt-in discovery. The PC-session event bridge scenario still proves close-before-activation and no mutation. No external reviewer/subagent was used.

## Independent-review remediation (2026-10-06)

All nine assigned findings have implementation and regression coverage. Each finding's regression was written before its fix. Host failures reproduced behavior assertions. Android ownership/recovery/coverage/lifecycle seams initially failed compilation until introduced; the ownership test was additionally mutation-checked with the original per-instance mutex and failed the blocked-reader assertion, then restored green. Navigation first failed on missing-target selection; fixture `nextCursor:null` and query-path assertion were corrected before final green.

| Finding | Cause and correction | Regression boundary | Commit |
| --- | --- | --- | --- |
| H1 | Different EncryptedStateStore instances had different locks: service openRead could roll back repository writes. Canonical file owner now serializes read/write/clear and all key operations. | EncryptedStateOwnershipTest: held atomic write + separate reader, drafts/pending survive, clear observed. | `7a68f6a` |
| H2 | Excluded global session events touched gap/watermark before eligibility. Fresh ordinary-session membership now comes first; absent IDs do not reset coverage or journals. | excluded subagent event 0/1/2 preserves ordinary completion and head. | `203d6f3` |
| H3 | Reconciliation snapshot could consume terminal cursor while terminal callback remained queued. It now owns pending state, not event admission sequence. | terminal delivered during held running/idle cold reads remains exactly one completion. | `b6b1e8e`, `1b1259e`, `f0ce9c5` |
| M4 | Downtime pending changes were invisible to old device cuts. Changed episode cut resets journals in both directions; sequence-only changes do not reset. | restart needed→cleared, cleared→needed, unchanged episode. | `272e4f7`, `1b1259e` |
| M5 | Page coverage was discarded and connection always claimed connected. Durable monitoring status drives live Settings and connection warnings in RU/EN. | NotificationPolicyTest serialization/degraded→ready; debug/release resource and lint gates. | `f2641e5` |
| M6 | Missing index/offline target rejected; intent consumed before selection. Refresh index or read authorized snapshot beyond index, retain intent until selected, no mutation. | RepositoryTest new/cold/retry/bounded-index and NotificationNavigationTest success predicate. | `c772af5`, `f0ce9c5` |
| M7 | Resume awaited network and could admit FGS after STOP. Main-thread generation owner cancels pending reads, checks immediately before start; sync comes afterward; failures persist blocked status. | NotificationResumeTest disk-held STOP, network-held start, platform exception. | `40d83a4`, `f0ce9c5` |
| M8 | Base-file existence bypassed API26 backup recovery. openRead first; only FileNotFoundException means empty state. | NotificationStoreRecoveryTest backup-only cipher/cursor/opt-in and actual absence. | `10074a5` |
| M9 | Startup opened all history serially and relisted N times. Zero opt-in has zero baseline IO, activation is background/global-budgeted, one list metadata reused with fresh opening header authorization; idle probes bounded/lazy. | 600 idle sessions, first enable, held startup/global timeout, lazy race; installed Cordis open-close/no-mutation checks. | `3bedc25`, `1b1259e`, `f0ce9c5` |

Final focused self-review was read-only over the full revision scope from `efae41d`, including working-tree/new files; no delegation. Corrected idle attention emission, sequence-independent pending comparison, delayed-busy navigation trigger and safe failure reporting. No dependency, SDK, manifest permission, relay wire, release installation or runtime/profile changes. Changes overlapping UI-polish remain focused on notification effects/selection and the existing Settings notification panel.

The lazy idle probe budget is eight sessions per minute, with ten seconds per pass; a previously unobserved idle pending request may therefore appear late in a very large workspace. This is explicit bounded recovery, not a startup scan or a claim of immediate idle-history coverage. Normal live pending events and known pending restarts are processed promptly. Real OS AtomicFile/KeyStore, FGS and Compose tap interaction still require the hardware gates below; JVM tests model the atomic race/backup boundary rather than running API26 framework code.

## Idle recovery re-review follow-up

Commits: `5b8794f` (fair retry/reactivation), `2892990` (authoritative idle evidence). Final host gates: 121 tests, 117 pass/4 intentional skips without Cordis; 121/121 pass with installed Cordis; both build/check exit 0. Focused self-review covered the full diff from `8ec5130`, including uncommitted tests; git diff --check passed. No delegation or push.

Host-only changes after `8ec5130`; no Android/runtime/dependency changes. Tests were added before each finding's implementation: the retry/exclusion/invalid/unprobed/fairness/reactivation cases reproduced failures, and the corrected adapter→feed tests were additionally checked against the original running-gated predicate (both failed). A held-baseline disable/re-enable test exposed a stale-generation reset and now passes with post-await abort checks. A real AbortSignal timeout test proves unprobed IDs survive the ten-second pass budget.

- Lazy discovery now rotates the batch before I/O and retains IDs after failures, exclusion and early exit. Failed probes have capped exponential retry spacing (2–16 reconciliation passes); a permanently broken chat does not block peers. Current+temporarily excluded IDs stay bounded to 10,000. Successful idle probes remain in rotation so later pending state is discoverable.
- A zero→one enabled-device transition discards the old observation generation, rebuilds its queue from a fresh list and runs a fresh asynchronous baseline with initializing coverage. Old suspended reads cannot publish pending state or resets into the new generation.
- Notification evidence no longer requires list-summary running:true. Installed user-question projection active.state=open is authoritative, continued/empty/settled views do not inherit pending from historic tool calls. Installed approval audit pairs are folded inside their open turn. A terminal stop makes leftover question/approval state non-alerting. The existing historical desktop notice is retained.
- Read-only runtime evidence: dsh-user-questions/lib/types/projection.js (active open/continued fold; results settle or continue), dsh-user-approval/lib/types/index.js and invariant.js (turn-enclosed asked/decided audit). The adapter→feed fixtures exercise running:false open question and approval, continued-only, cancelled-terminal stale state, episode clearing and close-before-activation/no mutation. They are not live production acceptance.

## Not accepted on hardware

Emulator smoke deliberately skipped: Pora_API_36 had an existing owner process, and later inspection found an already running read-only AVD on port 5580. It was not reclaimed, installed into or stopped. No physical phone or live DSH session was used. Lock-screen redaction/tap, actual FGS Stop, API26/33/34 behavior, Doze, process death, relay background reconnection and OnePlus overnight battery/delivery remain manual acceptance gates. specialUse distribution approval remains a separate gate.

Implementation limits are recorded in [Phase 1 as built](NOTIFICATIONS_DESIGN.md): best-effort foreground service, no boot resurrection/UnifiedPush, shared per-kind mute, inline pending cap, bounded asynchronous/lazy baseline and lazy retention. Gap/downtime completion loss is surfaced as limited coverage; platform display can be lost after a persisted receipt.
