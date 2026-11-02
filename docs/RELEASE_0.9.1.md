# DSH Mobile 0.9.1 — release notes and acceptance record

0.9.1 closes the open findings of [0.9.0](RELEASE_0.9.0.md). Upgrade app and host together, as for 0.9.0.

## Versions

| Component | Release identity |
|---|---|
| Android | `0.9.1`, `versionCode 18` |
| Host plugin | `0.9.1` |
| Mobile protocol | `/v1`, unchanged routes; host state schema stays 4 (one additive table) |
| Release DSH target | `0.2.1-rc.5`, unchanged |

## Changes

| Commit | Side | Change |
|---|---|---|
| `f12b51b` | host | Settled answers to legacy (no upstream call ID) questions are kept in the host's private SQLite state, so «Ответ: …» survives a host restart. Covers phone, desktop and skip answers. Bounds: 20 per session, 128 globally, 24 hours after settlement; a restart does not renew expiry. Details in [PROTOCOL](PROTOCOL.md). |
| `e717375` | host | Snapshot GET/SSE carry only the newest complete settled answers that fit 1 MiB of serialized JSON, so many long answers cannot push a snapshot over its 2 MiB limit after a restart. |
| `1d1dcf3` | Android | Removing a queued message no longer shows a transient «Не удалось удалить из очереди» while the removal receipt is still pending; queue receipts are polled like other pending receipts. A definite rejection still shows the error. |
| `76c7644` | Android | While a removal is in flight the queued item reads «Удаляем…» instead of the steering label «Направляем…». |
| `ci/green-tests` merge | tests, CI | Test-only fixes for races found on GitHub runners (listener cleanup, SSE end-of-stream, refused-route fixture, history recovery join, projection retry wait). Windows host test files run one at a time in CI and in the release workflow. Product code unchanged by this merge. |

Packaging: the host ZIP is built from a clean checkout. The two legacy `dist/notifications.js/.d.ts` files that 0.9.0 builds carried over (finding N1) are no longer included; no runtime file imports them.

## Upgrade

Follow [0.9.0 upgrade and rollback](RELEASE_0.9.0.md#upgrade-and-rollback) with 0.9.1 artifacts. Step 3 there (private `host.json` declaring an older DSH version than the running rc.5) applies to installations configured before rc.5; the owner's installation was reconciled before this release. The new answer-history table is created on first start; rolling the host back to 0.9.0 leaves it unused.

## Known limitations

Unchanged from [0.9.0](RELEASE_0.9.0.md#known-limitations). Still unverified on a physical phone: the failure alert (no safe deterministic failure route) and the exact alert delay under Doze.

## Verification

Recorded 2026-11-03 (MSK).

| Gate | Result |
|---|---|
| Independent code review of `769a0fe..e717375` | PASS after one fix: the first review found that many long persisted answers made the snapshot exceed its limit after a restart (HTTP 413); fixed in `e717375`, re-review PASS. |
| Host `npm.cmd run check`, merged tree `f5ba99b` | exit 0; with the installed rc.5 Cordis module ([DSH_INTEGRATION](DSH_INTEGRATION.md)): 370 tests, 370 passed, 0 skipped. Without it: 368 tests, 361 passed, 7 installed-Cordis tests skipped. |
| Android, merged tree `f5ba99b` | `testDebugUnitTest` 490/490; `lintDebug` 0 errors, 41 warnings; `assembleRelease` exit 0. |
| GitHub CI on `ci/green-tests` | Linux host, Windows host and Android green on two attempts of one run. |
| Queue remove on the phone (APK from `e717375`, host 0.9.0) | PASS, 2 runs — no «Не удалось удалить», item gone in 6–8 s, removed text never delivered, no extra turn. Found the «Направляем…» label, fixed in `76c7644` (test red before the fix, green after). |
