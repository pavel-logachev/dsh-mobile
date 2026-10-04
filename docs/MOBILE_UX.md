# Mobile surface brief

Mode: **Operate**. Owner-approved native Android control surface: dark graphite and restrained lime by default, with light and system-theme options. ChatGPT and Claude are usability references, not a demand to copy their branding or layout pixels.

## Direction contract

**THESIS:** Continue a desktop task from a phone without losing its state, across all permitted projects. A searchable home chat list makes navigation explicit; the conversation stays primary once opened.

**OWN-WORLD:** Material 3 native controls and semantic colors. Dark graphite surfaces, one restrained lime accent; warm off-white/olive light variant and a system option. System sans with Cyrillic; monospace for project/time/status/code. One 4/8dp rhythm, 48dp targets, no gradient/glass/metric tiles.

**STORY:** Know which PC/project/chat is active, send a task once, understand whether the PC accepted it, leave freely, then return to authoritative progress and output.

**FIRST VIEWPORT:** Home shows the DSH mark, compact connection pill, search and project filter chips, date-grouped chats and one new-chat FAB. Chat uses native Back, title and accented project label, Markdown timeline and a multiline composer above the keyboard. Its circular send action becomes confirmed stop while running. New chat is a searchable project/preset bottom sheet. Offline and uncertain delivery remain actionable.

**FORM:** User-pinned category-native chat direction, code-led implementation. No concept tournament or illustrative marketing mockup is needed. Native touch, Back and accessibility semantics govern all composition.

**FINISH:** unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance. Native screenshots from emulator/device, not a browser, are the evidence. No shipping raster assets are required for the current vector-only interface.

## Screens

- Pairing: primary offline QR scan through CameraX + ZXing, with secondary file import/manual JSON paste for a one-use invitation. Optional CAMERA permission is requested only on scan after a short rationale; denial/missing camera keeps import/paste available, permanent denial also offers app settings. Native Back, viewfinder, hint and optional torch; frames stay in memory on the phone, without logging, network access, telemetry or a Google Play services dependency. Show trusted host, endpoint, fingerprint and phone label before explicit confirmation; scanning never connects automatically. No raw DSH token or TLS ignore action.
- Chat: selectable text/code, tonal user message, assistant text without heavy cards, compact activity. No raw event/tool/JSON dump.
- Chats/new chat: permitted sessions, configured workspace selection and optional available preset. No global desktop workspace mutation.
- Connection/settings: host, last good sync, truthful unsupported background-notification notice, local forget distinct from server revoke.

## Essential states

Connecting, synchronizing, online, offline with stale state, revoked, incompatible protocol, TLS/identity failure. Online requires successful sync. Delivery separates sending, accepted, rejected and uncertain; uncertain provides receipt lookup, never blind resend. Draft survives navigation and process recreation. The task keeps running when observation closes.

## Accessibility and behavior

Material native Back; edge-to-edge plus system/cutout/IME insets; 48dp targets; system text scale; light and dark contrast; meaningful icon labels; no per-token TalkBack announcements. Auto-scroll only near the conversation end; otherwise offer new-messages action. Disable unsupported or unauthorized mutations for real, not only visually. Russian resources and complete English fallback; never translate user content or source paths.

## Review matrix

Phone compact portrait; keyboard open; dark/light; 1.3 font scale; long message/code; offline/uncertain/revoked; empty state; one ongoing conversation. Tablet polish is not a first-release claim. Screenshot passes are bounded: inspect a batch, fix material findings, re-check once; a separate reviewer judges remaining issues.
