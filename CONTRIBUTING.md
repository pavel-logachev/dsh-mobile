# Contributing

This is an independent DSH Mobile development preview, not an official DeepSeek project. The project is licensed under the [MIT license](LICENSE). Discuss substantial changes with the maintainer before implementation.

## Before changing code

Read [project instructions](AGENTS.md), [Architecture](docs/ARCHITECTURE.md), [Protocol](docs/PROTOCOL.md) and [Build](docs/BUILD.md). Keep native UI, transport and host adapter boundaries explicit. Changes to wire behavior need a corresponding version/capability decision and public-boundary tests.

Do not modify an installed DSH runtime, active profile, private session history, user infrastructure or production network as a side effect. Tests use synthetic fixtures or disposable state. A permitted workspace is not a security sandbox. Follow [Security](docs/SECURITY.md); report vulnerabilities privately rather than in public issues.

## Checks

Host checks, from `host/`:

```sh
npm ci
npm run build
npm test
```

Android checks, from `android/` with JDK 21 and SDK 36:

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

For Windows use the repository helper and instructions in [Build](docs/BUILD.md). A `NO-SOURCE` task is not a passed behavior test. Add targeted tests for changed authorization, pairing/revocation, delivery/reconnect or cancellation behavior. Never hide a failing check behind a new baseline or broad suppression without explaining the underlying issue.

## Review information

Describe scope, behavior change, exact commands/results and remaining gaps. Separate unit/fixture/emulator evidence from real DSH and physical-device evidence. For UI changes include synthetic-data screenshots in light/dark themes and increased font scale, with no private content. For upstream adapter changes state which DSH version/API was inspected.

Keep dependencies and action commits pinned and review updates against official sources. Do not commit caches, generated builds, private diagnostics, credentials, certificates or signing keys. No attachments, question/approval actions or push should be advertised until the negotiated capability is implemented and accepted. Do not promise exactly-once delivery or permanent background connectivity.

CI is a verification definition, not deployment. Commits, remotes, public publication, production installation and network changes must not be performed without explicit owner approval.
