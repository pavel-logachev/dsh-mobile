# Implemented Android design

Native Kotlin / Jetpack Compose / Material 3 **Operate** surface. The owner-approved dark graphite/lime direction (see the [native screenshots](screenshots/)) supersedes the old slate/teal drawer design. This is an Android companion for desktop tasks, not a dashboard or WebView. No gradients, glow, glass, attachment controls or nonfunctional menus.

## Tokens and typography

| Role | Dark (default) | Light |
| --- | --- | --- |
| background | `#0F1214` | `#F6F7F2` |
| surface / container / high | `#161A1D` / `#1B2024` / `#22282C` | `#F6F7F2` / `#ECEFE5` / `#E5E9DE` |
| onSurface / variant | `#E6E9E4` / `#9AA39E` | `#1A201B` / `#566054` |
| primary text/icons | `#C5F04A` | `#4E6A00` |
| primaryContainer / onPrimaryContainer | `#2E3A12` / `#DDF59A` | `#E4EFC6` / `#293600` |
| outlineVariant | `#2C3338` | `#C6CEBE` |
| error | `#FF8A80` | `#A9322A` |
| delivery warning / container | `#E6BC70` / `#382E1B` | `#79530E` / `#F7E7C5` |

Primary FAB, send/stop, create/import and selected project filters use lime `#C5F04A` fills with `#14180A` text in both themes. All other light-theme lime accents become accessible olive. Color roles are centralized in [Color.kt](../android/app/src/main/java/dev/dshmobile/app/ui/theme/Color.kt); there is no wallpaper dynamic color.

[Type.kt](../android/app/src/main/java/dev/dshmobile/app/ui/theme/Type.kt) deliberately maps Material roles: headlines 30/26/22sp, titles 20/16/14sp, body 16/14/12sp, labels 14/12/11sp. Body uses system sans/Roboto and native Cyrillic; project names, timestamps, status and code use **FontFamily.Monospace**. No bundled JetBrains Mono: platform mono avoids a font payload and licensing/download dependency while preserving scalable native text. Line heights are explicit, all sizes follow system font scale. Spacing uses 4/8dp rhythm, 16–24dp gutters; touch controls are at least 48dp. Shapes are 6/10/16/24/28dp with pills for search, filter chips and composer action.

[ThemePreference.kt](../android/app/src/main/java/dev/dshmobile/app/ui/theme/ThemePreference.kt) stores DARK / LIGHT / SYSTEM in ordinary `mobile_appearance` SharedPreferences, separate from encrypted pairing/drafts. Dark is the absent/invalid preference default. Settings changes apply immediately including system-bar icon contrast. Native authored outline vectors in [MobileIcons.kt](../android/app/src/main/java/dev/dshmobile/app/ui/MobileIcons.kt) share a 24dp viewport, 2-unit stroke, rounded caps/joins; no new icon dependency or raster assets.

## Information architecture

[MobileApp.kt](../android/app/src/main/java/dev/dshmobile/app/ui/MobileApp.kt) only owns lifecycle and minimal saveable Home/Chat/Settings navigation. Opening the app starts at **Home**, not a drawer. System/predictive Back returns to the list, closes sheets/dialogs natively, and canceled predictive gestures do not navigate. Filters, search, route and scroll positions survive Activity recreation; pairing secrets never enter saveable state. Repository/ViewModel contracts and secure persistence are unchanged.

- **Home:** DSH mark, compact connection pill and Settings; local title search; horizontal All/project/count chips ordered by activity; date sections Today / Yesterday / This week / Earlier using local dates, timezone and locale week start. Rows have two-line titles, mono project/time, text-labelled running dots and read-only state. Counts describe the received bounded session window, not a fabricated total. Pull-to-refresh reads canonical state. A scroll-collapsing New chat FAB and empty-state CTA respect online/permission/pending gates; truncated-list copy stays at the end.
- **Chat:** native Back, single visible title, accented project label, compact status affordance and a real Refresh action. No permanent online banners. One issue strip appears only while not online; synthetic demo copy is a slim truthful tag. User text is a right-aligned tonal bubble with timestamp; assistant output is unboxed native Markdown with a small DSH mark. Copy message/code uses the native clipboard plus “Copied” snackbar. Provisional text stays labelled; new-message jump appears only when the reader is away from the bottom. Activity is labelled running/waiting; the displayed duration is an estimate from the last task message, because the protocol has no run-start timestamp.
- **Composer:** up to six visible lines, native IME/navigation insets, circular lime send that becomes confirmed stop while running. Online/capability/executable scope/known activity/32 KB/busy/unresolved-delivery gates remain. Pending receipt lookup and confirmed local abandonment are available on both Home and Chat, so an uncertain create is recoverable without a chat. Never silently resend or infer cancellation completion.
- **New chat:** Material modal bottom sheet, folder/count/radio project rows (read-only projects visible but disabled), default = current filter or most recently active executable project, optional horizontally scrolling preset chips and full-width Create. Project search appears above eight projects; the actual project list is lazy and tagged for scrolling up to 100 projects. No arbitrary filesystem path control.
- **Settings:** grouped Computer, Connection, Appearance, Device sections; truthful remote route and last sync, three theme choices, supported/unsupported capabilities, confirmed local forget distinct from host revocation/task cancellation.
- **Pairing:** one “Connect your computer” hero, numbered three-step transfer/verify flow, primary import, secondary masked JSON, device label and existing trusted-preview confirmation. Address/host identity/fingerprint are selectable mono blocks. The invitation has no display name, so the dialog explicitly says it is available only after secure pairing instead of inventing one. Strict canonical invitation validation, certificate checks, debug loopback restriction, memory-only secrets and relay-origin-only preview are unchanged.

## Native Markdown and safety

[MarkdownParser.kt](../android/app/src/main/java/dev/dshmobile/app/ui/markdown/MarkdownParser.kt) parses ATX headings 1–3, paragraphs, bold/italic, inline code, backtick/tilde fenced code with language and partial-input state, bullets/numbers with one nested indent, blockquotes, rules and simple GFM tables. The [renderer](../android/app/src/main/java/dev/dshmobile/app/ui/markdown/MarkdownRenderer.kt) uses selectable Compose text only. HTML stays literal; links are styled inert text, not intents/URL callbacks; nothing executes or loads remote content. Code and tables scroll horizontally. The parser is regex-free and linear (hostile 131,072-character inputs are covered by time-bounded JVM tests). Messages up to 8,192 characters parse synchronously so the first frame is never empty; longer ones parse on `Dispatchers.Default`, showing plain selectable text on first composition and the last completed document during streaming updates. Parsing is bounded to 131,072 characters, 512 blocks, 8,192-character text chunks, 12 table columns/64 rows, with bounded inline nesting and delimiter indexes. Split code previews copy the whole received bounded fence; overflow/truncated headings and table cells are flagged, and ordinary Windows path backslashes are preserved. Longer messages show a preview notice; message copy retains original text. This is a small supported subset, not a CommonMark compliance claim.

## Opt-in screenshot stages (parent/operator only)

The existing [acceptance test](../android/app/src/androidTest/java/dev/dshmobile/app/acceptance/MobileAcceptanceTest.kt) remains the real-Activity ordered lifecycle suite; no existing script is modified. Its additional `redesignCapture` stage is intentionally opt-in and is **not** part of the current acceptance helper’s default matrix. Start the parent-owned synthetic fixture with `--multi-project`. The private version-1 envelope keeps all existing required expectation fields; optionally copy its `demoFixture` object to the envelope (`markdownSessionId`, `markdownAnchor`, `filterWorkspaceId`). If left nested inside `invitation`, the test reads it then removes it before importing into the unchanged strict production parser. Defaults are `demo-fund-plan`, `План портала фонда`, `demo-fund`. This opt-in mode is refused for isolated real-DSH canaries.

On an authorized **disposable** emulator only: install app and test APKs, provide the envelope through the existing private stdin/run-as transfer, and use non-secret arguments:

```text
adb -s emulator-5580 shell am instrument -w -r
  -e class dev.dshmobile.app.acceptance.MobileAcceptanceTest#fixtureJourney
  -e acceptanceStage redesignCapture -e acceptanceCapture home-dark
  dev.dshmobile.app.test/androidx.test.runner.AndroidJUnitRunner
```

Run `pairing-dark` before pairing, with an empty disposable app, font scale 1.0. Then run the unchanged `pairLifecycle` fresh-pair stage. Each subsequent capture invocation needs a new private envelope **without** its one-use invitation, a cold Home launch and font scale 1.0 except `home-font130` = 1.3. Names: `home-dark`, `home-project`, `chat-markdown-running`, `new-chat-sheet`, `settings-dark`, `home-light`, `home-font130`. The running fixture session must expose the Markdown anchor and cancel capability; capture asserts the stop control but never clicks it. Check `OK (1 test)` as well as no failure/crash markers: adb exit 0 alone is insufficient. Pull PNG + matching JSON from the existing app external `acceptance` directory into an ignored artifact directory. The JSON names native compositor source, dimensions, locale/font/uiMode, persisted theme preference and fixture-only/false-production provenance. APK/PNG hashing and restoring operator-owned font/night/locales/forwarding remain the parent’s responsibility. These are command instructions, **not a performed run**.

## Accessibility, motion and verification boundary

Connection status preserves `connection_state`, enum `stateDescription` and polite live region. Existing mutation/recovery/pairing tags remain; `chat_drawer` now labels the equivalent Back-to-list control, and acceptance explicitly opens a restored chat from Home. New tags cover search, filters, theme choices and screen boundaries. Titles/headings/selected radio semantics, meaningful icon descriptions, text-labelled states, selectable message content and disabled mutation controls remain native. No per-token live announcement or notification/camera permission is introduced.

Running dots pulse subtly with Material standard easing. FAB/sheets use Material motion; native predictive Back uses a small scale/fade. Animator duration scale is observed, with a static dot/Back fallback when system animations are disabled. Theme and screens have no decorative entrance motion.

JVM parser/presentation tests and the documented Android build/lint/test APK/release gates are the code checks. The opt-in `redesignCapture` instrumentation stage records eight native screenshot targets using the existing private fixture envelope and compositor/provenance conventions: dark Home, filtered Home, Markdown/running Chat, create sheet, Settings, empty Pairing, light Home, 1.3 font Home. It runs only against the explicitly synthetic multi-project fixture, never a live host, and does not send/create/cancel/forget tasks. Empty Pairing capture must precede pairing on a disposable app. Theme preference is restored after each stage; font/night/locale changes remain operator-owned. Home rows open a retained current snapshot while offline; other uncached chats stay disabled until an online sync.

Public [synthetic native screenshots](screenshots/README.md) illustrate the interface, not physical-device readiness. Visual comparison, clipping/IME/font scale, TalkBack/predictive gestures, large-session performance and physical-phone/mobile-network acceptance are separate operator gates. Native code does not use HTML/CSS; the app ships vector controls, not screenshot rasters.
