# DSH Mobile 0.9.3 – release notes and acceptance record

0.9.3 finishes the phone-task watch: the ongoing "waiting for the task result" notification now settles when a task ends unsuccessfully or is stopped by the user, and a user stop is silent. It also fixes a reconnect hang seen on Android 17, keeps the chat timeline steady while sending and streaming, and groups running chats at the top of the chat list. Upgrade the app and host plugin together. The relay is unchanged since 0.9.2.

## Versions

| Component | Release identity |
|---|---|
| Android | `0.9.3`, `versionCode 20` |
| Host plugin | `0.9.3` |
| Relay | unchanged from 0.9.2 |

## Changes

### Phone-task watch (host)

| Commit | Change |
|---|---|
| `b10dd6b`, `0266a10` | Settle durable unsuccessful turn endings as `failed` instead of leaving the watch waiting. Older checkpoints are re-scanned. |
| `1da624d` | An explicit user Stop settles the watch as `rejected` with `terminalReason: user-stop`; the phone clears the watch without an error alert. A queue discard stays non-terminal, as before. |
| `f5b3eac` | Preserve already proven outcomes across re-scans and resume bounded journal scans from a saved cursor. Checkpoint `consumptionVersion` 4. |

### Chat and chat list (Android, host)

| Commit | Change |
|---|---|
| `c031419`, `6b16ae1` | Preserve timeline anchors across sends and streaming, so the list does not jump; a finished answer stays pinned relative to the bottom. |
| `07a66ce` | Chat search matches only the presented user and assistant text; service-only messages stay in agent activity. Preserve conversation space in short windows. |
| `a1afac6` | Explain notification permission before the first system request. |
| `7675dba`, `c0374b6`, `d8c1d32` | A "Working now" section at the top of the chat list for running chats, chats with running subagents and chats waiting for an answer. The home roster refreshes every 5 seconds in the foreground (first page only, at most 12 requests per minute, with backoff). The host returns up to 50 active chats with the first page (`activeItems`, `activeItemsTruncated`). Search keeps date-only order. |

### Connection (Android)

| Commit | Change |
|---|---|
| `f027b2e`, `6d42b2c` | "Reconnect" in the chat list and in a chat opens a fresh bounded recovery window, also after automatic retries were exhausted. |
| `7252150`, `eb04c4b`, `68d188b` | Track foreground per UI owner. Previously a late STOP from one owner could mark the shared connection as background while the app was visible, so the app stayed offline and "Reconnect" did nothing until the app was reopened. Manual reconnect uses the normal selection cleanup. |
| `7252150` | Release-enabled, rate-limited `DshMobileConn` logcat diagnostic: connection stage, exception class, short code, attempt counters, elapsed time and network flags. It records no URLs, hosts, tokens, ids or message text. |
| `0dff1c4` | After Stop, the pending card ("Sending…" / "Check delivery") settles as soon as the host confirms the stop. Previously it stayed 1–2 minutes after the task had stopped. |

`3a42c7c` bumps the app to 0.9.3 (20) and the host to 0.9.3.

## Artifacts and provenance

| Artifact | Built from | SHA-256 |
|---|---|---|
| `dsh-mobile-0.9.3.apk` | `0dff1c4` | `b32452925af84544a5c34343a3b4d78034aa78315f8f0ab871d2c247871cc554` |
| `dsh-mobile-host-0.9.3.zip` | `bac4018` | `ae87c33de836e663338a85828d2ea5a3a365b916b7949d3f1f12a186c275123c` |

The host source has not changed between `bac4018` and `0dff1c4` (`git diff --stat bac4018 0dff1c4 -- host relay` is empty). The host ZIP was built reproducibly: a repeat build produced identical bytes, and an independent reviewer reconstructed the same bytes from source. APK signing certificate SHA-256, unchanged from earlier releases: `F9080C63B49A0BDE6B74AA2ADF7E9531D6C4F305978A24D3F5D271A39F1B7B95`.

## Upgrade

- Update the app and plugin together. For plugin installation and rollback, follow [SETUP](SETUP.md#10-upgrade-rollback-revoke-and-uninstall) with 0.9.3 artifacts.
- The signed APK was installed with `install -r` over earlier 0.9.3 candidates, and those over 0.9.2, on the owner's phone; pairing was retained.
- No relay update is needed.

## Known limitations

- A very short task can finish before the ongoing watch notification appears; the answer alert is still posted.
- Agent-question responses are shown in a footer after the messages, not at their chronological place.
- After airplane mode is switched off, reconnection took 10–23 seconds on the owner's phone with a VPN active. During that time the TLS handshakes to the relay failed while Android already reported a validated network; the app kept retrying.
- Direct LAN upload has not been verified on the phone for this release.
- The failure alert on a physical phone remains unverified, as in [0.9.1](RELEASE_0.9.1.md#known-limitations).

## Verification

Recorded 2026-11-07 and 2026-11-08 (MSK).

| Gate | Result |
|---|---|
| Android on `0dff1c4` | `testDebugUnitTest`: 551 tests, 87 suites, 0 failures; `lintRelease`: 0 errors, 40 warnings. |
| Host on `bac4018` | `npm.cmd run check`: 389/389, 0 skipped. |
| Relay | 19/19 (relay source unchanged since 0.9.2). |
| Independent review | Watch fixes, chat-list section, reconnect fixes and the Stop card fix each passed independent review after fixes for the findings raised. The host change package passed independent package review. |
| Phone | OnePlus CPH2747, ColorOS on Android 17 (SDK 37). |
| Stop | Two runs, Stop about 10 s after the task started; host `rejected` / `user-stop` both times, no error alert. On the candidate before `0dff1c4` the watch was gone within 1.4 s of the host terminal state; on the final APK the whole pending card was gone within 1.4 s. |
| Doze | A 4-minute task completed in forced idle with the screen off and locked: the watch was gone within 1.4 s; the answer had been delivered before the app was opened and was not repeated. |
| "Working now" | The running test chat was listed under "Working now" and moved under "Today" 2.5 s after it finished. |
| Reconnect | After automatic retries were exhausted in airplane mode, "Reconnect" in a chat started a connection attempt 53–56 ms after the tap. |
