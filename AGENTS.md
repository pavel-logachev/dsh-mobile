# DSH Mobile

Native Android companion for a local DeepSeek Harness installation. This project is independent of other repositories and the installed runtime.

## Boundaries

- Build a real Android client, not a WebView wrapper. Keep agent execution, subscriptions and provider credentials on the desktop.
- Never modify the installed DSH runtime, its profile, the user's existing sessions or other agents as a side effect of development.
- No public GitHub publication, public port exposure, router/VPN/firewall changes or production service replacement without explicit permission.
- No credentials, private chats, machine-specific home paths or real network addresses in tracked files or test fixtures. Keep runtime state, logs and generated artifacts out of Git.
- DSH integration must be based on inspected installed protocol/SDK, not invented endpoints. A mock-adapter PASS is not live integration acceptance.

## Quality

- Prefer small explicit modules and typed contracts. Native UI in Kotlin/Jetpack Compose; host integration architecture is documented in docs/ARCHITECTURE.md when settled.
- Test meaningful behavior at public boundaries: pairing expiry/revocation, authentication, permissions, duplicate command delivery, reconnect/resync and cancellation.
- Do not claim exactly-once execution unless proven across the actual upstream boundary. Surface uncertain delivery rather than retrying side effects blindly.
- No insecure release defaults or TLS verification bypasses. Scope development transport exceptions to debug and local test targets.
- Android background notifications must be an explicit supported capability, not a permanent socket promise.
- Preserve existing work; read before editing. Record commands and actual results in the acceptance report. Clearly separate tests, emulator checks, physical-device checks and production readiness.
- Product copy is Russian-first with English resources. Accessibility, loading/error/offline states, IME insets and font scaling are part of implementation.

## Collaboration

- Assigned agents work only in their agreed paths. Do not delegate from children.
- Do not commit or publish unless asked. Keep project setup reproducible and documented.
