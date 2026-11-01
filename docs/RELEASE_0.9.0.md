# DSH Mobile 0.9.0 — release notes and acceptance checklist

**Status: source candidate; final artifact and physical verification pending.** This is the single release record. It replaces the intermediate local acceptance drafts; their old measurements, hashes and machine-specific logs remain private historical evidence, not results for this candidate. No publication or production rollout is implied.

## Versions and build identity

| Component | Release identity |
|---|---|
| Android | `0.9.0`, `versionCode 17` |
| Host plugin | `0.9.0` |
| Mobile protocol | `/v1`, additive capabilities/routes; invitation and relay versions remain separate |
| Release DSH target | `0.2.1-rc.5` |
| Other retained DSH support | `0.2.0-rc.2`, `0.2.1-alpha.1`, on historical full/registry canary evidence; not rerun by the rc.5 repair |

The current full isolated rc.5 canary covers **25 checks**, including the real durable-journal prompt-outcomes path. This establishes isolated synthetic-model compatibility, not an external-model, active-profile or physical-phone result. See [compatibility evidence](DSH_CANARY.md#compatibility-evidence). rc.4 is excluded: inspection/similarity to rc.5 is not separate acceptance evidence.

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
- The host publishes the first nonempty provisional text of each new attempt immediately, retaining the existing throttle for subsequent deltas. No real-phone latency multiplier is claimed; record comparable before/after samples below.
- Snapshot ingress, Unicode boundaries, question history, image fetch ownership and destructive local reset retain bounded handling. Image previews bound each fetch to 12 seconds and retry one transient/timeout failure after 750 ms, then show the retry affordance; cancellation and permanent failures do not retry. Phone acceptance must still exercise new and cached images.

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
- Physical scenarios below are still release gates. Historical measurements and earlier successful builds do not fill these cells automatically.

## Release handoff plan

After reviews and authorized source/test/docs commits, build/sign/package from the exact clean release commit, preserve matching private mappings/receipts and fill every applicable Verification field. Compare inventory and artifact hashes with the reviewed delivery. Resolve or explicitly accept failures/limitations; tag/publish/roll out only after separate authorization. No commit, tagging, upload or operational migration is performed by this document.

## Verification

<!-- Intentionally empty results: the lead fills these from final-build evidence after acceptance. -->

| Gate | Result / counts / exact build / evidence |
|---|---|
| Clean source commit/tag and build identity | _Pending_ |
| Host `npm.cmd run check` (exit, pass/fail/skip counts) | _Pending_ |
| Android JVM tests (tasks, counts, failures) | _Pending_ |
| Android lintDebug/lintRelease (errors/warnings) | _Pending_ |
| Full isolated rc.5 canary (25 checks, cleanup/counters/receipt) | _Pending_ |
| Signed APK SHA-256, version 0.9.0/code17, certificate/alignment | _Pending_ |
| Host ZIP SHA-256 and full inventory SHA-256 | _Pending_ |
| Signed APK permission/release-guard review | _Pending_ |
| Send and answer agent question | _Pending_ |
| Queue, steer/remove queue item and cancel active turn | _Pending_ |
| Image + non-image file upload/view/download | _Pending_ |
| Completion / failure / needs-answer alerts in background + tap | _Pending_ |
| Completion / failure / needs-answer alerts after process death | _Pending_ |
| Network loss/return and Wi-Fi↔mobile switch; no mutation replay | _Pending_ |
| Doze / screen-off / sustained notification run | _Pending_ |
| Reboot with pending phone task; no idle resurrection | _Pending_ |
| Speed before/after (same route/data, cold/warm, samples/method) | _Pending_ |
| Upgrade preserves pairing/data; reviewed rollback available | _Pending_ |
| Remaining findings and explicit release decision | _Pending_ |
