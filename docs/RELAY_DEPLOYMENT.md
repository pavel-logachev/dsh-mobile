# Relay: bounded first deployment

This is a reusable operator procedure, not a record of any public deployment. Use your own Linux server, private SSH access and reviewed Caddy configuration. No shared service or existing personal infrastructure is supplied.

## Target and non-goals

Choose an SSH alias such as `my-vps`, deployment account, site directory such as `$HOME/example-site`, and Caddy container such as `example-site-caddy`. Supply the exact container name with `--caddy-name`, its fresh full Docker ID with `--caddy-id`, and the exact bind source/destination with `--mount-source` / `--mount-dest`. The name, ID, startup, read-only directory bind and baseline network IDs are pinned in immutable transaction state and rechecked before mutations and rollback. Example startup: `caddy run --config /etc/caddy/Caddyfile --adapter caddyfile`.

The relay uses **only** `dsh-mobile-relay-internal` (`Internal=true`); Caddy is its sole other endpoint. No host ports, public raw DSH listener, router/VPN/DNS changes, existing Compose edits or unrelated application fixes. Preserve the existing site's certificate, redirects, fallback and application health. Compare known endpoints and Docker health before/after, not guessed URLs.

## Image and runtime policy

Build/check the relay, then build the image using the pinned [Dockerfile](../deploy/relay/Dockerfile). Its base-image digest is a public dependency pin, not a deployed server identity. Verify compiled artifact hashes and the exact image ID before transfer; use your own reviewed image ID, never a historical receipt or mutable tag as proof.

[compose.yaml](../deploy/relay/compose.yaml) is a declarative reference. **Do not run Compose up/create in production** for this procedure: the transaction driver creates resources with write-ahead ownership intent and an explicit image ID. Runtime policy: UID/GID `10001:10001`, read-only root, init, drop ALL capabilities, no-new-privileges, 16 MiB noexec/nosuid/nodev `/tmp`, named durable volume `dsh-mobile-relay-data` at `/data`, 512 MiB / 0.5 CPU / 128 PIDs, rotating json-file logs 10 MiB × 3, healthcheck and restart unless-stopped. Docker inspection verifies these settings. Transaction rollback never removes the durable volume.

## One coordinator, no shell state

[transaction.py](../deploy/relay/transaction.py) is a Linux/Python 3 standard-library CLI. Every operation shares a persistent owner-private lock at `$HOME/.local/state/dsh-mobile-relay/caddy.lock`, never session-dependent `XDG_RUNTIME_DIR`. External command children inherit that flock descriptor: killing only the driver cannot release the lock while its mutation child runs. Command budget: 20 seconds; lock acquisition: 90 seconds; each operation: 180 seconds. Interrupted forward operations **must rollback**, not resume apply.

Each transaction stores strict JSON and immutable backup/candidate/spec/driver snapshots under `$HOME/.local/state/dsh-mobile-relay/transactions/TXN`. No file is sourced/evaluated. The user-systemd service uses absolute Python/snapshot paths and pins spec SHA-256. A UTC `OnCalendar` timer with `Persistent=true` survives SSH disconnect and runs after a missed reboot deadline. Rollback failure retries every 10 seconds, subject to systemd start-rate limits; require operator attention. Service timeout: 300 seconds. Never delete lock/state/units during an active transaction.

Paths must be canonical absolute ASCII paths without spaces, `%`, dot segments or symlink components. SSH and the user manager must use the same HOME; nondefault XDG state/config locations are refused. Secret directories: 0700; snapshots: 0400; mutable JSON/units: 0600. Trusted nonsecret unit directories may be 0755. Only `.config`, `.config/systemd`, `.config/systemd/user`, `.local` and the configured site's direct HOME-child ancestor derived from the validated `mount_source` may be group-writable, with current UID/primary GID ownership, NSS proof of no other primary/supplementary group members, and no extended access/default POSIX ACL. This narrow exception is rechecked on every invocation, including snapshot rollback; it never applies to secret state or unknown siblings. No unrelated directory is chmodded.

Arming requires named relay/network absent. Reusing a durable volume requires inspected explicit `--reuse-data`. Creation uses transaction and random guard labels, with saved intent before mutation and exact IDs recorded after inspect. Known absence + both labels recover a create/record crash. A never-started owned container may have empty NetworkID only with exact `Status=created`, `Running=false`; after startup the network ID must match.

## Additive candidate Caddyfile

Prepare a separately reviewed candidate from the **complete exact current file**, not a reconstructed site. Insert only the namespace before existing fallback, preserving unrelated bytes:

```caddyfile
handle_path /dsh-mobile-relay/* {
    log_skip
    reverse_proxy dsh-mobile-relay:8088
}
```

`handle_path` strips the prefix, so relay receives `/v1/...` and `/healthz`. Scoped `log_skip` keeps `X-DSH-Join` out of shared access logs without suppressing unrelated logging. If your Caddy rejects it, stop. Validation uses `docker exec -i PINNED_ID caddy validate --config - --adapter caddyfile` on stdin. Candidate install is atomic only inside guarded apply, under lock + active timer/deadline checks. Reload verifies host and mounted-file hashes and uses the pinned container/config.

References: [Caddy handle_path](https://caddyserver.com/docs/caddyfile/directives/handle_path), [log_skip](https://caddyserver.com/docs/caddyfile/directives/log_skip), [validate/reload](https://caddyserver.com/docs/command-line), [Docker service policy](https://docs.docker.com/reference/compose-file/services/).

## Reviewed operator sequence

1. Fresh read-only preflight: full Caddy ID/name, exact SOURCE/DEST/RW bind, startup, network memberships, config bytes/hash, Docker/Caddy/Python/systemd availability, user lingering, safe ownership/ACL/NSS, disk space and existing website/TLS/application health. No pruning. Keep receipts private.
2. Export the verified image outside Git. After approved transfer compare archive SHA-256, load and verify the exact image ID. Transfer only reviewed driver/candidate.
3. Arm a fresh transaction. It snapshots files, validates the candidate and verifies an independent user timer **before** production mutation. On failure inspect state and rollback; never reuse the transaction ID.
4. Apply: labelled internal network + hardened relay, health, additive Caddy attachment, exact topology, atomic candidate install and pinned reload. This does not commit; failure leaves the guard armed.
5. Within the deadline: namespace health 200, unauthorized WebSocket 401, a **separate temporary route** full WSS/inner-TLS canary and unchanged website certificate/redirect/fallback/application baseline. Privately provision/revoke the temporary route. Health alone is not acceptance.
6. Explicitly commit the exact candidate hash only after all gates; otherwise rollback and verify restoration/cleanup. No deadline extension/updater is provided. Physical-phone acceptance is separate.

Example on your target (fill freshly reviewed IDs/hashes; no secrets in arguments):

```sh
TXN=first-relay-test
SITE_DIR="$HOME/example-site"
CADDY_CONTAINER_NAME=example-site-caddy
python3 transaction.py arm "$TXN" \
  --live "$SITE_DIR/caddy/Caddyfile" \
  --candidate "$HOME/relay-deploy/Caddyfile.candidate" \
  --original-sha256 ORIGINAL_SHA256 --candidate-sha256 CANDIDATE_SHA256 \
  --caddy-id PINNED_FULL_CADDY_ID --caddy-name "$CADDY_CONTAINER_NAME" \
  --mount-source "$SITE_DIR/caddy" --mount-dest /etc/caddy \
  --caddy-path /etc/caddy/Caddyfile --image-id sha256:REVIEWED_IMAGE_DIGEST \
  --delay 900
python3 transaction.py apply "$TXN"
python3 transaction.py check "$TXN"
# After acceptance, choose ONE:
python3 transaction.py commit "$TXN" --candidate-sha256 CANDIDATE_SHA256
python3 transaction.py rollback "$TXN"
```

Timer diagnostics: `systemctl --user status dsh-mobile-relay-rollback-TXN.timer` and matching service/journal. Commit and rollback share the lock; durable committed transition is the linearization point. A queued timer observes committed and does nothing. If rollback wins, commit refuses. Do not stop rollback service during commit.

## Rollback/failure handling

- Original live hash: cleanup without unnecessary reload; prior restore/reload intent still requires verification.
- Candidate hash: atomically restore immutable original, verify mounted hash, reload successfully, then remove only owned resources.
- Unknown live hash, mutated snapshots, wrong name/ID/mount/network/labels or unexpected endpoint: refuse clobber/detach/removal; retain state/guard and diagnose. Never redefine hashes to bypass refusal.
- Interrupted rollback is retry-idempotent: restore/reload/stop/remove/detach. Stop the exact owned relay (`docker stop --time 10 ID`), verify stopped, remove exact ID, disconnect only recorded Caddy attachment and remove only the empty labelled network. Unrelated resources and durable volume remain. Already-missing owned resources are tolerated after earlier successful cleanup.

## Private route provisioning and orphan recovery

```sh
python3 transaction.py provision "$TXN" --output "$HOME/private/new-relay-capability.json"
```

Output directory must be owner-only 0700 and destination absent. The helper pins the owned running container, provisions to random container `/data/.route-capability-UUID.json`, copies to exclusive private host recovery state, validates permissions/schema/canonical token, atomically publishes by no-clobber hard link, and removes exact staging files. Only a digest is printed, never capability content or Docker diagnostics. Keep credentials outside Git.

On failure/interruption a route may exist. Preserve exact stages and `transactions/TXN/route-UUID/recovery.json` (container/output/route IDs, never token). **Do not blindly retry.** Identify/revoke the exact route via `docker exec PINNED_RELAY_ID node dist/cli.js revoke-route --state /data/relay.sqlite --route ROUTE_ID`, then remove only retired exact stages. A death during provisioning can leave an orphan: reconcile durable route state first. If publication succeeded but staging cleanup failed, do not overwrite the valid host capability.

## Local checks and limitations

```sh
# Use a vetted EXISTING local image with sh, tar, Python 3 and NSS support.
# No image pull, Docker socket mount, host mount or network is used.
python deploy/relay/run_local_tests.py --image YOUR_LOCAL_PYTHON_TEST_IMAGE
```

The runner requires local `desktop-linux` context and refuses implicit image selection. Fake Docker/systemctl tests use real Linux files, flock, subprocesses and SIGKILL races. They cover hash/identity/topology refusal, crash windows, rollback retries, commit/watchdog ordering, inherited lock, narrow private-group paths and private provisioning failures. On native Linux, run `python3 deploy/relay/test_transaction.py` as a nonroot user with a private primary group.

Optional separate image checks: `python deploy/relay/verify_image.py` compares current compiled relay artifacts; `python deploy/relay/smoke_image.py` creates temporary uniquely named Engine resources and validates health/hardening/cleanup without public ports. Compose quiet validation: temporarily set harmless `RELAY_TRANSACTION=local-check`, run `docker compose --project-directory deploy/relay -f deploy/relay/compose.yaml config --quiet`, unset it. Never print resolved private config.

Wrappers call only the coordinator. `record-resources.sh` is disabled and reconcile is read-only. Local fake systemd/Caddy tests do not prove live user-timer persistence or public service acceptance. A hung daemon, clock jump, hostile same-UID/root operator or externally changed resources needs operator diagnosis; this is not a generic disaster-recovery controller.

Caddy's additive attachment survives restart of the same container, **not recreation** from unchanged Compose. A recreated Caddy requires independent identity checks and a separately reviewed rollback/reattachment plan. This first-deployment driver refuses replacement/update use; do not edit the site's Compose silently or reuse a finalized transaction.
