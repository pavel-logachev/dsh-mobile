# Phone-task notifications for Android 0.9.0

## Release status: host contract required

This Android change is **not end-to-end release acceptance**. The inspected v1 host
snapshot and command receipt do not durably map a phone request to its final turn
outcome. A receipt proves admission, not completion. The bounded transcript, current
`activity`, current failure and transient activity detail cannot prove what happened
to an earlier phone turn after another turn replaced it. Inferring completion from
`idle`, a timestamp or an unrelated desktop answer would notify the wrong task.

The Android implementation therefore fails closed when the authoritative outcome
is absent. On the existing host it can register and restore a watch, but cannot
promise a completion alert. The host files are outside this change's ownership.
The additive field below is a **proposed prerequisite**, not an assertion that the
current host implements it. No new endpoint is assumed.

## Required additive snapshot contract

The existing authenticated session GET and snapshot SSE must expose a device-scoped,
durable `promptOutcomes` array, independently of the bounded message window:

```json
{
  "promptOutcomes": [
    {
      "requestId": "00000000-0000-4000-8000-000000000001",
      "turnId": "authoritative-turn-id",
      "status": "completed",
      "summary": null
    }
  ]
}
```

- `requestId` is the existing phone command id; no transcript/body or credential is
  persisted in the Android watch. Only this authenticated device's prompts belong
  in this projection. Desktop prompts and other devices must not acquire these ids.
- `turnId` is a stable upstream turn identity. Several accepted steering/queued
  requests may share a turn; Android emits at most one outcome notification for it.
- Statuses understood by the Android prototype are `queued`, `running`, `waiting`,
  `completed`, `failed`, `rejected`. `waiting` means this task needs user input and
  settles its watch. `rejected` settles without an alert. A later completion after
  a waiting alert does not produce a second alert for that turn.
- Failed outcomes provide a concise plain-text `summary` (up to 2048 UTF-8 bytes).
  The host must preserve outcomes long enough to cover the 24-hour watch lifetime,
  including its own process restarts, and publish changes through session SSE.
  Admission and subsequent outcome association need an upstream proof, not an idle
  heuristic. Retention and capacity must not silently evict a still-valid watch.
- The Android decoder currently accepts at most 1024 unique request outcomes per
  snapshot, valid UUID request ids and nonblank bounded turn ids. These limits need
  agreement with the host implementation before release.

A host change must also resolve transport capacity: the inspected host allows only
three simultaneous streams per device (including the foreground UI), while this
prototype opens one per distinct watched session. More than two background watched
sessions can therefore be rate-limited when the UI is observing another chat. The
32-watch persistence cap is **not** a proven 32-session live-delivery capacity. A
bounded multiplexed watch-change signal or an agreed host quota/transport design is
required; do not ship the current multi-session transport as release-ready.

A host change must supply durable request-to-turn association, terminal/attention
state and failure summary, plus tests covering completion while Android is offline,
subsequent desktop turns, bounded transcript eviction, duplicate/reordered events,
queued/steered requests, host restart and per-device authorization. Then exercise
real Android against that host; mock snapshots alone are not integration evidence.

## Android lifecycle and bounds

1. The single local switch defaults on. Android 13+ still requires the user's
   `POST_NOTIFICATIONS` permission; the settings switch requests it, and denial
   prevents background service startup. System notification settings remain linked.
   Disabling clears watches/outbox, stops the service and cancels notifications.
2. An explicit phone send stores its session/request watch **before** HTTP admission.
   Known rejection or provably unsent delivery removes it. Uncertain delivery keeps
   it; notification recovery never replays a mutation or resends a prompt.
3. Watches are a separate encrypted atomic no-backup record. At most 32 watches and
   32 undelivered alerts are retained; newest admissions win at capacity. Each
   watch expires 24 hours after registration. Expiry is checked on restore/resync
   and enforced by the running service's earliest-watch deadline. The encrypted
   record has an existing 512 KiB limit. Completed-turn tombstones retain at most
   2048 entries for 24 hours. These are bounded guarantees, not unlimited history.
4. No valid phone watch or pending outcome means no service and no foreground
   notification. The retired `connection` channel/notification is removed on
   service migration. Only active watches (including their immediate settlement
   outbox drain) permit a foreground service. Last settlement drains its outbox,
   removes the foreground notification and stops the service.
5. For active tasks, the new channel requests `IMPORTANCE_MIN`, no badge, sound or
   vibration. Copy is neutral: waiting for a task sent from this phone. Android can
   promote a foreground notification or display its own background-service notice:
   **an invisible active foreground service cannot be guaranteed**. Android's
   documented foreground minimum is LOW, so the requested MIN is not a guarantee
   of a hidden status icon. Idle operation does not need a foreground service.
6. Reconnect/network-change recovery opens the existing watched session streams
   before authoritative GETs. Streams trigger fresh GET reconciliation; no host
   notification feed status, journal cursor or historical feed event decides a
   task's outcome. A failed chat does not prevent successful unrelated outcomes
   from being drained. Retries use the existing bounded 1–5 second reconnect delay;
   there is no periodic idle poll or host mutation.
7. A durable outbox is written before posting. The notification uses a stable
   session/turn tag, immutable chat PendingIntent, private lock-screen body and
   `onlyAlertOnce`. A crash after notify but before acknowledgment reuses that tag.
   Policy check and synchronous platform notify share the notification-store lock
   with disable/reset. Notification delivery is not a claim of exactly-once host
   command execution.
8. Service recreation uses `START_STICKY` only after a valid watch/outbox guard.
   Normal `BOOT_COMPLETED` (after credential unlock) and `MY_PACKAGE_REPLACED`
   receivers use that same guard; empty/expired/disabled/unpaired/permission-blocked
   state does not start background work. Receivers never dispatch a prompt.

## Android 14/15 constraints and limits of verification

The manifest retains `specialUse`, declares `FOREGROUND_SERVICE_SPECIAL_USE`, and
explains the bounded phone-task subtype. It does not select `dataSync`, camera,
microphone, media projection, media playback or phone call to bypass restrictions.
Android documents boot/package replacement as background-start exemptions, while
Android 15 prohibits boot startup of the listed restricted service types; `specialUse`
is not in that list. Store distribution approval for special-use justification is
not established by this code change.

Sources checked on 2026-11-01:

- [Background foreground-service start exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 boot restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed)
- [Foreground service types and special use](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
- [Foreground notification priority minimum](https://developer.android.com/develop/background-work/services/fgs/launch)
- [NotificationManager IMPORTANCE_MIN](https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_MIN)

Robolectric/pure tests validate watch admission, persistence projection, outcome
reconciliation, dedupe, failure/attention, expiry/cap, cancellation/interleavings,
empty service startup, permission guard and requested channel policy. They do not
prove OEM scheduling, actual Android 14/15 reboot/process-kill behavior, Doze,
notification sound delivery or the missing host contract. Instrumentation source
compilation is distinct from running device tests.

No guarantee applies after user force-stop, while notification permission is denied,
after expiry/cap eviction, while the phone or computer is offline, or while Android
prevents background work. Ordinary system-kill/reboot restoration is implemented,
but must still be checked on disposable Android 14 and 15 targets before release.

## Android audit fixes

- AND-01: destructive forget deletes mandatory credentials independently of optional
  history/notification decryption/key failures; failed file unlink remains an error.
- AND-02: turn failure is a two-line disclosure with a selectable scrollable detail
  dialog rather than an unbounded block above the transcript/composer.
- AND-03: retryable projection GET failures keep their retryability through refresh,
  including a first load without an existing snapshot; existing six-attempt bound
  and permanent/cancellation behavior are retained.
- AND-04: legacy in-memory answer fallback is capped at 32, cleared on session
  change and forget; it is not a growing cross-session archive.
- AND-05: image preview retries only transient failures, propagates cancellation,
  and keys preview/viewer producers to the current cache and attachment identity.
