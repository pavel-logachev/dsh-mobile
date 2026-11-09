# DSH Mobile 0.9.0 — release notes and acceptance checklist

**Status: candidate `761687d` (2026-11-02). Host activated through the DSH change pipeline, signed APK installed on the owner's phone; physical results are listed in Verification.** This is the single release record.

## Versions and build identity

| Component | Release identity |
|---|---|
| Android | `0.9.0`, `versionCode 17` |
| Host plugin | `0.9.0` |
| Mobile protocol | `/v1`, additive capabilities/routes; invitation and relay versions remain separate |
| Release DSH target | `0.2.1-rc.5` |
| Other retained DSH support | `0.2.0-rc.2`, `0.2.1-alpha.1`, on historical full/registry canary evidence |

The current full isolated rc.5 canary covers **25 checks**, including the real durable-journal prompt-outcomes path. This establishes isolated synthetic-model compatibility. See [compatibility evidence](DSH_CANARY.md#compatibility-evidence). rc.4 is excluded.

Version alone is insufficient to distinguish experimental builds. Release handoff must identify **one exact clean source commit**, one signed APK, one complete host ZIP and its inventory, with full SHA-256 values and matching receipts in Verification. Do not overwrite an immutable installed directory or apply a query-string cache buster to reuse a modified same-version build. A prior locally installed build or old hash is not provenance for the final release commit.

## Changes

### Phone-task notifications

- Notifications follow prompts started on the phone, not every desktop session. New authenticated `GET /v1/sessions/{id}/prompt-outcomes` uses capability `promptOutcomes`; it projects durable `unknown/queued/running/waiting/completed/failed/rejected` outcomes without dispatching commands.
- Completion, failure and needs-answer alerts use durable bounded encrypted watch/outbox state. Waiting keeps watching; a final outcome replaces the waiting item for that chat. Tap opens the chat. Recovery never resends a prompt.
- No always-on idle service, permanent connection notification or coverage banner. Active phone-task watches still use a foreground service; its system/OEM notification cannot be guaranteed invisible.
- Pending valid watches/outbox restore after process death, unlocked reboot or package replacement subject to pairing, permission and OS restrictions. Force-stop requires a user launch. Watches expire after 24 hours and are bounded to 32; there is no universal closed-app delivery guarantee.
- The old notification feed and its client/settings/events/stream paths are retired. Existing legacy host feed data is left untouched and unused; fresh state does not create that feed schema. Older apps see notifications unavailable on the new host. **Upgrade app and host together.**

The [Android notification contract](NOTIFICATIONS_0.9.md) and [protocol](PROTOCOL.md) define exact projection, dedupe, current-state/episode, transport and crash limitations. Historical [feed design](NOTIFICATIONS_DESIGN.md) and [feed acceptance](NOTIFICATIONS_ACCEPTANCE.md) do not describe the new feature.

### Chat and responsiveness

- Turn/attempt failures are displayed in chat with bounded safe details instead of appearing as a missing answer; this does not repair upstream provider/account errors.
- Network return wakes foreground reconnect and coalesces network flaps. Online state follows validated capabilities/workspaces/presets and the first authorized session page; remaining pages load separately under the existing bounds.
- Cached stale content can appear immediately; fresh chat opens stream-first rather than doing a full GET before SSE. Authentication failures remain terminal and are not hidden by fallback reads.
- The host publishes the first nonempty provisional text of each new attempt immediately, retaining the existing throttle for subsequent deltas.
- Snapshot ingress, Unicode boundaries, question history, image fetch ownership and destructive local reset retain bounded handling. Image previews bound each fetch to 12 seconds and retry one transient/timeout failure after 750 ms, then show the retry affordance; cancellation and permanent failures do not retry.

### Command reliability and resource bounds

- Ledger identity/epoch plus a best-effort sidecar witness can detect a restored database that is older than surviving evidence. A recovery hold blocks fresh admission while receipts/history and exact known-receipt replay remain readable.
- Operator CLI: `recovery-status`, `recovery-hold`, `recovery-release --new-epoch`. Manual hold must be armed **before reopening command admission** when witness rollback cannot be detected; reconciliation against DSH remains manual.
- No client-visible epoch fencing after release. A requestId whose receipt was lost can be admitted as new. Epoch rotation records the operator's decision, not proof of non-execution; there is **no exactly-once execution guarantee**.
- At most 32 unsettled dispatches host-wide. Overflow returns `429 rate_limited` before admission, without a receipt. The ten-minute host deadline marks a post-admission timeout `uncertain`; actual settlement retains resource ownership, and late results do not overwrite terminal receipts.
- Post-ACK diagnostic failure does not reclassify admitted work. Shutdown/failed runtime claim cleanup preserves command uncertainty and releases host-owned locks/handles. Invalid or unreadable surviving witness data prevents release rather than inventing a safe epoch.
- Claimed staged files remain owned until admitted dispatch settles. Fresh startup orphans have a 24-hour grace; failed cleanup is retried. Authenticated path-free cleanup counters remain available through capabilities but are not displayed in the app.
- Session index pagination uses `(updatedAt,id)` keysets. Provisional text is bounded to 2 MiB/1024 blocks; oversized/gapped evidence does not become fabricated final text. Snapshot SSE heartbeats remain independent of a blocked scope lookup.

## Upgrade and rollback

1. Obtain the reviewed signed APK, host ZIP and checksums from one published release, or explicitly approved private candidate delivery. Do not invent asset URLs, use unsigned verification APKs or infer identity from filenames. Verify metadata, certificate, hashes and source provenance.
2. Back up the complete owner-private host configuration, TLS identity, state and relevant profile insertion using a SQLite-safe backup or after companion disposal. Keep backups, keys, invitations and detailed receipts outside Git. Preserve the existing Android signing identity and app data; do not uninstall to bypass a certificate error.
3. Review an operator migration if private `host.json` and the profile disagree about DSH version: the known older rc.2 declaration must be reconciled to the actual verified rc.5 runtime in the next authorized install/restart window. Preserve unrelated config, TLS/relay credentials and grants. Do not silently rewrite live config; verify both declarations and the loaded capabilities after activation.
4. Upgrade **both** app and host to 0.9.0. Use the reviewed [managed installer](SETUP.md#10-upgrade-rollback-revoke-and-uninstall), `-Upgrade -Activate`, whole immutable build and a fresh module URL. Wait for old listener disposal before mounting the new version. A restart is separately approved and ends running DSH turns; do not start a replacement DSH server.
5. Keep host state schema 4; legacy feed tables/data may remain but are unused and not migrated/backfilled by the retired feed. New phone-task watch state is separate from command/draft state. Confirm package permissions, especially boot restore, against the actual signed APK and release guards.
6. For rollback, unmount only the companion and use the reviewed managed previous build/receipt. Do not blindly restore an old full profile over unrelated edits or downgrade Android by deleting app data. Decide app/host rollback together: older apps/hosts do not provide the new notification contract or all recovery safeguards. Restoring an older command database needs the [hold/reconciliation procedure](SETUP.md#restoring-the-command-ledger-from-a-backup) before any new admission.

Automatic prompt resend is safe only with transport proof that **request headers were not sent**, at most three attempts using the same requestId. Never automatically resend after admission. A lost response or receipt 404 never proves non-execution. An uncertain outcome must be inspected on the desktop before an intentional new send.

## Known limitations

- Desktop DSH and the computer must remain on. Mobile is a native subset: no plugin/skill management or mobile action approvals; answerable questions are a different supported capability. One pairing targets one host.
- No FCM/UnifiedPush delivery path. Doze, OEM restrictions, offline devices, force-stop, denied permission, watch expiry/cap eviction and notification-store/platform crash windows can delay or lose alerts. No exactly-once sound, visual delivery or host execution claim. An active-task FGS notification cannot be guaranteed invisible on all OEMs.
- Upstream JSONL currently reads the **whole session log** before the companion can apply its retained-page/byte bounds. Those bounds do not cap upstream transient allocation.
- History is bounded, not a complete offline archive: 100 visible messages/2 MiB per snapshot, older surface recovery at most 16 pages/16 MiB. Android's presentation-only stale/read-only cache retains at most 8 snapshots, 3500 KiB of serialized snapshot UTF-8, 24-hour age, each at most 2 MiB; the index has separate bounds. Cache is neither authorization nor command reconciliation.
- Workspace grants filter access, not agent filesystem/tool powers. Revocation blocks later admissions/reads, not already dispatched work; local forget is not host revoke. Invitation pins do not authenticate a wholly substituted invitation, so verify trust material through a trusted channel.

## Release handoff plan

After reviews and authorized source/test/docs commits, build/sign/package from the exact clean release commit, preserve matching private mappings/receipts and fill every applicable Verification field. Compare inventory and artifact hashes with the reviewed delivery. Resolve or explicitly accept failures/limitations; tag/publish/roll out only after separate authorization. No commit, tagging, upload or operational migration is performed by this document.

## Verification

Recorded 2026-11-02 (MSK). Physical results come from one OnePlus CPH2747 session, 02:32–02:52, against host build `0.9.0-68fad87d…` (before the question-bridge fix); the fixed host was activated at 03:35.

| Gate | Result / counts / exact build / evidence |
|---|---|
| Clean source commit/tag and build identity | PASS — clean `release/0.9.0` at `7a697960202ada92715d30d3254e93a9ee1bbbe3` (host fix: legacy `ask_user_question` waits exposed to the phone). `a66c938..7a69796` changes only `host/src/question-bridge.ts` and `host/test/question-bridge.test.ts`; Android sources are identical to `a66c938`, the commit recorded in the signed APK build context. Android `dev.dshmobile.app` 0.9.0/code 17; host 0.9.0. This Verification record is committed on top as a docs-only change. |
| Host `npm.cmd run check` (exit, pass/fail/skip counts) | PASS — `a66c938`: exit 0, 343 tests, 343 passed, 0 failed, 0 skipped (including installed-Cordis tests). `7a69796` (fresh independent run): exit 0, 345 tests, 345 passed, 0 failed, 0 skipped, including installed-Cordis tests and the real rc.5 legacy-question regression (red on `a66c938`, green on the fix). |
| Android JVM tests (tasks, counts, failures) | PASS — `:app:testDebugUnitTest --rerun-tasks` at `a66c938` (Android unchanged at `7a69796`): exit 0; 75 suites, 458 tests, 458 passed, 0 failures/errors/skipped. |
| Android lintDebug/lintRelease (errors/warnings) | PASS with warnings — fresh `:app:lintDebug`/`:app:lintRelease` at `a66c938`: exit 0; 0 errors, 41 warnings, 1 hint per variant. |
| Full isolated rc.5 canary (25 checks, cleanup/counters/receipt) | PASS — DSH 0.2.1-rc.5, full runs at `a66c938` and `7a69796`: each exit 0; 25 checks (7+6+4+8); forbidden network/subprocess/outside writes, listeners and active listeners 0; effects disposed, synthetic runtime removed. Receipt SHA-256 `7a69796`: `080ace323811dae7718ab9d1eabda970e02b757ea9288fe8d45adf75b681a253`; `a66c938`: `929578a7adc3fa0178cd761912d6a80ac57b433d32ff0df29a4b7346cd718451`. Synthetic model only. |
| Signed APK SHA-256, version 0.9.0/code17, certificate/alignment | PASS — `dsh-mobile-0.9.0-private-release.apk` SHA-256 `013a1a7d347d3baefe2f7e73ac3051f10445030d6ab887695db58ccd51643ed0`; certificate SHA-256 `f9080c63b49a0bde6b74aa2adf7e9531d6c4f305978a24d3f5d271a39f1b7b95`, one RSA-3072 signer, v2/v3 verified; `zipalign -c -P 16 -v 4` exit 0. Installed `base.apk` on the phone has the same SHA-256. |
| Host ZIP SHA-256 and full inventory SHA-256 | PASS — final `dsh-mobile-host-0.9.0.zip` from `7a69796`: SHA-256 `90d7ff46b25f16e82fef923590dc823201064f8b7234b2a76254ad3521f5fa8b`, built twice byte-identically; inventory 85/85 payload, 86/86 tree; immutable digest `0675d120af13c3a772164d10fca6e2331da1b0192ea6254fe346a831f3ad51a8`. Differs from the `a66c938` build (ZIP `bb1dad06…`, digest `68fad87d…`) only in `dist/question-bridge.js` and its inventory hash. Independently reviewed (PASS) and activated through the DSH change pipeline 2026-11-02 03:35: batch committed, 0 errors, package post-check 3/3, smoke turn passed, quick health without FAIL; profile loads the new digest. |
| Signed APK permission/release-guard review | PASS — exactly the 8 permissions allowed by `tools/android-release.ps1` and the release workflow, including `RECEIVE_BOOT_COMPLETED` and `ACCESS_NETWORK_STATE`; 0 missing/unexpected. |
| Send and answer agent question | **FAIL (fixed in host)** — send/answer PASS (`[DSHM-QA]` prompt → exact reply). Agent question with two options: "needs answer" alert arrived and opened the right chat, but the chat showed "waiting on the computer" with no options. Root cause: DSH rc.5 legacy `ask_user_question` sends no `wait.callId`; the bridge required it. Fixed in `7a69796` with a real-rc.5 regression and an isolated HTTP answer flow. |
| Queue, steer/remove queue item and cancel active turn | PASS (remove/cancel) — item queued during a running `pwsh` turn, removed; active turn cancelled after confirmation; no queued/long replies appeared. Finding: remove first showed a transient "could not remove" error before the item disappeared. |
| Image + non-image file upload/view/download | PNG and TXT upload PASS; PNG fullscreen view PASS (one fetch). TXT open reached the system viewer ("saved"). |
| Completion / failure / needs-answer alerts in background + tap | Needs-answer alert + tap opened the correct chat: PASS. Completion alert arrived in background (with content intent): PASS. |
| Upgrade preserves pairing/data; reviewed rollback available | PASS (host) — after both pipeline activations (02:25 and 03:35) the phone kept its pairing without re-pairing: app showed "PC online" at 02:30 and was used for the session above; at 03:40 host `remote-status` reported `connected=true, ready=true, activeGrants=1, stale=false`. Previous immutable host builds and the pipeline rollback point remain available. |

### Verification update — final candidate (2026-11-02, 10:00–21:30 MSK)

The table above records the 02:32–02:52 session and stays unchanged as history. This update records later physical runs on the same OnePlus CPH2747 and the fixes they caused. Times are MSK.

**Final candidate.** Source `release/0.9.0` at `761687d`. Fixes after `7a69796`, all found during physical acceptance:

| Commit | Side | Fix |
|---|---|---|
| `e4d646d` | host | The phone shows «Ответ: …» for legacy questions answered on the phone, instead of «Агент продолжил без ответа». |
| `f85dea2` | host | After a phone answer the desktop GUI question card closes; only the desktop waiter is cancelled, never the agent turn. |
| `cf77a14` | host | Answers use the point session header instead of four full session-catalog scans; the scans were about 11 s of about 12.5 s from tap to tool result on a large session store. |
| `6f9160a` | Android | A pending answer receipt is no longer shown as «Ответ не отправлен», and answer receipts are polled while pending. |
| `3fc8910` | Android | Completion alerts carry the chat title, not «Чат без названия», also after reboot restore. |
| `2e22481` | Android | A self-retired `HostApi` transport is replaced, so «Подключить снова» can recover from a stuck «Нет связи». |
| `4c4538f` | Android | Opening settings twice no longer makes settings its own back target; the screen could not be left without a restart. |
| `1c99318` | Android | No completion alert for the chat currently visible in the foreground. |
| `761687d` | Android | No question alert for the visible chat; a question alert is cancelled when that chat is opened or its question resolves. |

**Checks and builds.**
- Host `npm.cmd run check` at `cf77a14`: 360/360, 0 skipped, installed-Cordis tests included.
- Android at `761687d`: `testDebugUnitTest` 480/480; lint 0 errors, 41 warnings.
- Host ZIP from `1c99318` (host unchanged since): SHA-256 `d988ff5d4860cc9d0f189366f700cf2cc5b48b7b5cb66d849142e1387b072271`, built twice byte-identically; immutable digest `30e85c02a945757c75b96c4363f7f29717a19bf4e518ce2b9aa00f52939e3e3c`. N1: the two legacy `notifications.js/.d.ts` files are carried over from earlier builds.
- Independent review PASS. Activated through the DSH change pipeline at 21:13:44: batch committed, 0 errors, smoke passed; relay `connected=true, ready=true, activeGrants=1`.
- Signed APK from `761687d`: SHA-256 `73b73cf667f9933232b7cf668f2e20048354df75a57432b6b04c6af7a57cd6eb`; build tests up-to-date at 480/480; installed 21:51:10; `base.apk` on the phone has the same SHA-256; certificate `f9080c63…` (unchanged); version 0.9.0, code 17; installed with `adb install -r`; pairing kept.

**Physical results.**

| Scenario | Result |
|---|---|
| Question Alpha/Beta answered on phone, foreground and background | PASS (10:02–10:10 and 15:24, 18:18, 21:22). |
| Desktop question card after a phone answer | PASS (18:19) — the owner observed the desktop card closing. |
| Answer card after leaving and reopening the chat | PASS — «Ответ: Beta», no «Агент продолжил без ответа» (15:24, 18:22). |
| Answer delivery feedback | PASS (21:22) — no «Ответ не отправлен», no stale «Отправляем…». |
| Answer latency | 18:18, before `cf77a14`: «Отправляем…» until at least +12.6 s, settled card and reply at +16.9 s. 21:22, after `cf77a14`: settled card and agent reply already at the first sample, +2.2 s. 2 s sampling. |
| Completion alert in background, tap opens the chat | PASS (10:02–10:35 run). |
| Completion alert title | PASS (18:19) — the notification showed the actual chat title, not «Чат без названия». |
| Process death with a pending watch | PASS (10:15) — «Ответ готов» delivered after the process died. |
| Wi-Fi↔mobile switch and full offline/reconnect | PASS at the UI boundary — no duplicate command seen. Not an exactly-once claim. |
| Forced Doze | Alert delivered under forced idle (10:20:23). |
| Reboot with a running phone task | PASS (15:39–15:40) — app restored by the boot broadcast without user launch; «Ответ готов» at 15:40:34.8; foreground service stopped at 15:40:35.1; no services at 15:49. The owner unlocked the phone. |
| Idle: no foreground service | PASS (15:49, 18:10; also in the 10:02–10:35 run). |
| TXT download and hash | PASS (10:02–10:35 run). |
| Settings opened twice, then back or arrow | PASS (21:25). |
| No completion alert for the visible chat | PASS (21:22). |
| Question alert for the visible chat | FAIL on `1c99318` (21:22: «Нужен ответ» posted while the chat was open and left after the answer); fixed in `761687d`, physical re-check PASS (21:55): no «Нужен ответ» for the open chat, no alert left after the answer; settled card at +2.2–4.4 s. |
| Speed, coarse 3–4 s sampling | Median cold start 106 ms; launch to online about 6.66 s; plain send to reply about 3.44 s (10:02–10:35 run). |

**Open findings.**
- Queue remove showed a transient «could not remove» error.
- Legacy-question answer history is kept in host memory only and is lost on a host restart.
- Private `host.json` declares DSH `0.2.0-rc.2` while the runtime is rc.5.
