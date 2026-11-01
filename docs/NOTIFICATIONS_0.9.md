# Phone-task notifications — Android 0.9.0

## Implemented contract and release boundary

Android uses the agreed additive `/v1` **prompt-outcomes endpoint**, not a proposed
session snapshot field or per-watched-session SSE. Version remains **0.9.0 / 17**.
The existing capability style is `capabilities.promptOutcomes: true` in
`GET /v1/capabilities` (not `features.promptOutcomes`). Absent/false means no watch
registration and no promised result alerts. Settings keeps one local switch and
shows a neutral support hint instead of the result-alert rationale. The switch is
purely local: no notification-settings GET/PUT or feed connection exists. Legacy
`capabilities.notifications: false` and `notificationCapabilities.feed: false` do
not gate watches; only `capabilities.promptOutcomes` does. Old encrypted feed
preferences/cursors are ignored on upgrade and not written back.

This is Android + contract-faithful MockWebServer acceptance, **not live host or
physical-device acceptance**. No prompt is replayed by notification recovery.
Actual host journal derivation, authorization, restart durability and transcript
window eviction must be accepted independently on the host and then end to end.

## Wire and poll ownership

`GET /v1/sessions/{sessionId}/prompt-outcomes` uses existing bearer authentication,
repeated `requestId` query parameters (1–32 distinct canonical UUIDs), optional
opaque `since`, and `waitSeconds` in 0–25. Android canonicalizes/collapses query IDs.
A response must contain a string `token` and exactly one outcome per requested ID,
in request order. Each entry requires canonical `requestId`, one of
`unknown|queued|running|waiting|completed|failed|rejected`, and ISO-8601 UTC
`updatedAt`. `turnId` is optional. `summary` is an optional string **only for failed**,
at most 2048 UTF-8 bytes with no broken surrogate pairs. Unicode/astral text and
embedded newlines are preserved; no extra plain-text restriction is invented.
HTTP responses have a **128 KiB client transport budget**, separate from the host's
2048-byte summary bound. This is a local resource limit, not a claimed protocol
maximum: unusually large optional IDs/tokens could exceed it and fail closed.
Malformed entries fail closed. No idle, timestamp, transcript or feed heuristic
can declare a phone task finished.

One service-owned coordinator serializes **all** poll calls and outbox delivery:

- Restore, explicit service start/app foreground, network/reconnect and a newly
  registered request restart the owner and begin with non-waiting checks.
- Tokens are in-memory and scoped to the exact ordered request set and session.
  Changing the request set invalidates its token. Process recreation starts fresh.
- A single session group holds up to 25 seconds. Multiple groups are traversed
  sequentially with at most a one-second hold each, so one quiet session does not
  monopolize the device for 25 seconds per group. At most one held/requested poll
  is in flight. A full 32-group sweep can still include 32 one-second holds plus
  network/host processing time; there is no cross-session push guarantee.
- Equal-token timeout is a normal 200 response; the next poll uses that token.
  A changed token/outcome is reconciled immediately. The endpoint has a separate
  35-second call/read budget, not the ordinary 15-second metadata GET budget.
- There is no periodic sleep on successful reads. Retryable online failures use
  the existing bounded, jittered 0.5–5-second backoff. Without an active network,
  the loop suspends until a network callback restarts it (or watch expiry). A host
  unreachable over an otherwise present network remains an online-error case.
- Cancellation closes and awaits retirement of the old transport before admitting
  the next generation. Watch expiry is a deadline even while offline or held.
- Notification watches use **no SSE**. Foreground chat SSE remains unrelated to
  notification delivery. The retired notification-settings/events/stream client,
  feed models, page delivery, remote preference sync and feed-only summary/mode
  machinery and tests have been removed. Notification channels keep the existing
  alert IDs/user sound settings; unused feed/silent/summary channels are retired.

## Durable watch and notification state

1. An eligible phone send persists `(sessionId, requestId, expiresAt)` **before HTTP
   admission**. Known rejection/proven unsent removes the watch; uncertain delivery
   retains it. Desktop prompts do not register watches. Capability false/absent
   prevents registration; restore rechecks capability before delivering anything
   and clears watches/outbox when the feature is unsupported.
2. Watch/outbox state is a separate encrypted atomic no-backup record, not the
   command/draft record. At most **32 watches and 32 queued alerts**, newest wins
   on overflow. A watch expires **24 hours** after registration, checked at restore,
   every reconciliation, and by the running loop deadline. The record is bounded
   at **512 KiB**. Turn/episode state and delivered-key tombstones each retain at
   most **2048 entries**, pruned at their 24-hour retention/expiry boundaries.
3. `unknown`, `queued`, `running` do not settle or alert. `rejected` settles without
   notification. `completed` and `failed` settle and queue «Ответ готов» / failure
   with summary. `waiting` queues «Нужен ответ» but **keeps watching**. A repeated
   waiting projection is silent; an observed transition out of waiting and back
   starts a new waiting episode. Changing `updatedAt` alone is not a new episode.
4. Several requests with the same session/turn share dedupe state. Waiting episode
   and final outcome have separate durable keys. All phases for a chat use one
   stable device/chat notification tag, so final completion/failure **replaces**
   that chat's waiting item and may alert once in its own right. Tap opens the chat;
   lock-screen content is private, with RU-first titles and English resources.
   The endpoint has no chat title: these alerts use the localized unnamed-chat
   fallback rather than fetching a transcript just for its title.
5. The outbox is persisted before platform notify. An attempt marker is persisted
   before crossing NotificationManager; acknowledged delivery then removes the
   outbox entry and writes its tombstone. A process crash after notify but before
   acknowledgment reuses the same chat tag with `onlyAlertOnce`, while a fresh
   waiting/final phase may alert again. Disable/reset and the final policy check +
   synchronous notify share the notification-store lock.
6. No watch or pending outbox means **no service and no permanent notification**.
   Last settlement drains its outbox, removes the foreground notification and
   stops the service. The retired `connection` notification/channel is removed.
   `START_STICKY`, unlocked `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` use valid
   watch/outbox, device pairing and notification-permission guards. Restore never
   dispatches a prompt. Android 13+ retains `POST_NOTIFICATIONS` flow and the single
   local switch; disabling clears watches/outbox, stops service, cancels alerts.

## Explicit protocol / Android limits

- `turnId` is optional in the agreed contract, even for an alertable status. Android
  must not discard a proven outcome, so without it dedupe falls back to that
  request's identity. **Cross-request once-per-turn dedupe cannot be proven when
  the host omits turnId.** Supplying a stable turnId is needed for merged-request
  dedupe; Android adds no new required wire field.
- The endpoint is a current-state projection, with no waiting episode ID/history.
  If a complete waiting→running→waiting transition occurs while Android is offline
  or between polls, it cannot distinguish two episodes from one. It alerts once
  per **observed** episode; `updatedAt` is not specified as an episode identifier.
- A notify/store commit cannot be atomic across process death. A crash after the
  attempt marker but before actual notify may make first recovery silent; stable
  tags prevent duplicate visible items, not a mathematical exactly-once sound
  guarantee. User dismissal between notify and crash recovery is also outside
  exactly-once visual delivery. This is not exactly-once host command execution.
- Active watches still require a foreground service using the existing `specialUse`
  declaration. The channel requests MIN importance, no sound/vibration/badge and
  neutral task copy. **An active FGS notification cannot be guaranteed invisible
  on all OEMs**; Android may promote it or show its own service notice. Idle service
  has been removed, not Android's foreground-service disclosure requirement.
- No delivery guarantee applies during force-stop, denied notification permission,
  expiry/cap eviction, phone/computer offline, or OS background-work restrictions.
  A durable outcome can be checked on reconnection while its watch remains valid.
  Store approval for special-use justification and physical Android 14/15 reboot,
  Doze, process-kill/OEM behavior and sound are not established by local tests.

Existing Android references retained for follow-up device acceptance:
[FGS background-start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[Android 15 boot restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed),
[special-use type](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[foreground notification minimum](https://developer.android.com/develop/background-work/services/fgs/launch),
[IMPORTANCE_MIN](https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_MIN).

## Verification

Run from `android` with the project's JDK/SDK environment:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --offline --no-daemon
```

The project wrapper `tools/android-build.ps1` sets that environment without tracked
machine-local configuration. Local tests use existing JUnit4, coroutine tests,
MockWebServer and Robolectric/Compose; no DI/library migration is required.

`PromptOutcomesIntegrationTest` exercises actual authenticated HTTP and endpoint
projection for completion, Unicode failure, waiting→completion replacement,
rejection, unknown→completion, desktop unknown, duplicates, equal-token timeout,
changed token, >15-second hold, restart mid-poll, concurrent multi-session reads,
encrypted process restoration/crash replay, absent capability, cap/expiry.
`WatchPollLoopTest` covers callback-driven offline recovery, foreground/reconnect
non-waiting checks, cancellation join and backoff/expiry. `PhoneOutcomeWireTest`
checks strict response/query boundaries; `PhoneWatchTest`, `WatchCoordinatorTest`,
`NotificationStoreRecoveryTest`, `WatchPlatformTest` and
`PhoneNotificationSettingsTest` cover durable phases, policy interleavings,
NotificationManager replacement/tap/summary, permission and rendered settings.

## Retained Android audit fixes

- AND-01: destructive forget deletes mandatory credentials independently of optional
  history/notification decryption/key failures; failed unlink remains an error.
- AND-02: failure uses a two-line disclosure and selectable scrollable detail dialog.
- AND-03: transient projection GET retryability survives refresh/first load; existing
  six-attempt bound and permanent/cancellation behavior remain.
- AND-04: legacy in-memory answer fallback is capped at 32 and cleared on chat change/forget.
- AND-05: image preview retries only transient failures, propagates cancellation and
  keys preview/viewer producers to current cache and attachment identity.
