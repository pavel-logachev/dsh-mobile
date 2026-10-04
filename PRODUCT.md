# DSH Mobile

## Product

An independent native Android companion for a user's existing DeepSeek Harness host. It is not an official DeepSeek product and does not bundle a new model service. Agent execution, provider subscriptions, desktop tools and canonical conversation history remain on the host computer.

## Audience and scene

A trusted DSH user accesses desktop tasks from a phone, often away from the desk and across unreliable Wi-Fi/mobile connections. The preview serves one trusted host. Public distribution must not require hard-coded personal infrastructure.

## Approved direction

Use a familiar, chat-first native interface comparable in usability to common chat clients. Projects, task activity and technical details should be reachable without crowding the conversation. This is an Operate surface, not a decorative dashboard. Use the approved dark graphite/lime default with light and system-theme options, accessible native controls and Russian-first copy with English fallback.

## First acceptance slice

Pair a client with a host, browse permitted existing sessions, create a session in a configured workspace, send a text task, observe execution, disconnect/reconnect without silently repeating a command, read the resulting conversation, and interrupt an owned running task. Expose uncertain delivery honestly. A physical-phone test over mobile internet is distinct from emulator and fixture acceptance.

## Architecture constraints

- No installed DSH source changes or interception of provider credentials.
- No mandatory paid external service or new cloud account for core functionality.
- No public exposure of the raw DSH interface. Pair/revoke devices and constrain workspace scope.
- Host tasks continue independently of Android Activity lifecycle; reconnect re-queries canonical state.
- Treat upstream protocol version and unsupported capabilities explicitly.
- Do not claim background push, offline execution or arbitrary attachments until implemented and tested.
- Prepare for GitHub publication with reproducible builds, generic configuration, documentation and a secrets-free tree. Publication requires a separate user action.

## Expansion

File attachments and downloads, structured ask-user/approvals, full task/executor detail and optional push are follow-up slices once the core transport and security boundary have passed acceptance. They remain product goals, not advertised implemented capabilities.
