# Relay: bounded first deployment and container image update

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

First-deployment `arm` requires named relay/network absent. Reusing a durable volume requires inspected explicit `--reuse-data`. Creation uses transaction and random guard labels, with saved intent before mutation and exact IDs recorded after inspect. Known absence + both labels recover a create/record crash. A never-started owned container may have empty NetworkID only with exact `Status=created`, `Running=false`; after startup the network ID must match.

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
6. Explicitly commit the exact candidate hash only after all gates; otherwise rollback and verify restoration/cleanup. No deadline extension is provided; existing relay image updates use the separate schema-2 path below. Physical-phone acceptance is separate.

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

Caddy's additive attachment survives restart of the same container, **not recreation** from unchanged Compose. A recreated Caddy requires independent identity checks and a separately reviewed rollback/reattachment plan. The first-deployment `arm/apply` path still refuses replacement use. Use the separate `update/cutover` path below for image updates only; never edit the site's Compose silently or reuse a finalized transaction.

## Existing relay: guarded container image update (schema 2)

This is **not** a state migration or Caddy updater. Independently review the new image, state backward compatibility and the driver before use. Keep the exact old image/container; no pruning. Run the fresh preflight from step 1 above, export/load the verified immutable new image, and save before-state receipts privately. Require the existing hardened relay healthy, the dedicated internal bridge containing exactly Caddy and relay, and user lingering. Do not change Caddy, Compose, routes, tokens, volume or network ownership labels.

`update TXN` snapshots the old full Config/HostConfig, image, complete mounts, explicit network inputs/aliases and Caddy identity/config hash in owner-private state. Engine-generated container ID/name, default hostname, IP/MAC/endpoint IDs and DNSNames are lifecycle output, not independently reconstructed run inputs; the old hostname itself is preserved on the candidate. It creates a **never-started** candidate under an unpredictable temporary name using the same durable volume, labels, environment and runtime policy. The candidate already has the production alias in its saved network settings but has no live endpoint until start. It is never run alongside the old relay. No isolated image canary is implemented here; an independently reviewed image smoke test can run with its own temporary volume/network before arming.

Before stopping the old relay, the candidate's complete effective Config/HostConfig/mounts/network-input spec must round-trip to the pinned old spec, with only image ID changed. Unsupported extra settings or changed image defaults fail closed; diagnose and review rather than weakening the comparison. The old container is retained, not reconstructed on rollback. Specs may include private environment/config values: **never print/copy the spec or full docker inspect into shared logs or local evidence**. Only hashes and nonsecret identity/status receipts leave private server state.

The same persistent flock serializes schema 1 and 2. `update` arms an immutable snapshot user-systemd rollback service and a UTC persistent timer (default 600 seconds) **before candidate creation/cutover**. External mutations inherit the lock. It checks active timer/deadline, Caddy file hash on host and in the pinned container, and the old and candidate specs again before cutover. The first working-path mutation follows a durable `applying` transition and printed UTC cutover timestamp. Interrupted cutover is rollback-only:

1. Stop exact old ID; rename to `dsh-mobile-relay-rollback-TXN` and retain it.
2. Rename candidate to `dsh-mobile-relay`; start it (its explicit production alias becomes live).
3. Automatically check candidate health (up to 45 seconds while starting), HTTPS relay health and website **exactly 200** with curl TLS validation, unchanged other-container name/ID map, and exact Caddy/new-relay endpoint set. No redirects/TLS bypass. A failed forward operation or gate immediately attempts rollback; if refused/failed, independent timer stays armed and operator diagnosis is required.

`check` repeats the gates; `commit --image-id EXACT_ID` repeats them and only then durably commits/disarms. The lead must separately check PC host reconnect using the printed cutover UTC timestamp, and compare other-service status/website baseline before committing. WSS/inner-TLS canary is optional for this update path and reported **unverified** unless a separately reviewed safe temporary-route procedure is available; schema-2 `provision` is deliberately unsupported. Phone/large-upload acceptance is separate. Do not commit a failed required host gate.

Example (replace uppercase values with freshly verified nonsecret values):

```sh
TXN=image-update-unique
python3 transaction.py update "$TXN" \
  --live "$HOME/example-site/caddy/Caddyfile" \
  --caddy-id PINNED_FULL_CADDY_ID --caddy-name example-site-caddy \
  --mount-source "$HOME/example-site/caddy" --mount-dest /etc/caddy \
  --caddy-path /etc/caddy/Caddyfile --old-id PINNED_FULL_OLD_RELAY_ID \
  --image-id sha256:REVIEWED_NEW_IMAGE_DIGEST --delay 600 \
  --website-url https://example.test/ \
  --relay-health-url https://example.test/dsh-mobile-relay/healthz
STATE="$HOME/.local/state/dsh-mobile-relay/transactions/$TXN"
PIN=$(cat "$STATE/spec.sha256")
# Independently verify timer is active, deadline and snapshot ExecStart, then:
python3 "$STATE/driver.py" cutover "$TXN" --spec-sha256 "$PIN"
python3 "$STATE/driver.py" check "$TXN" --spec-sha256 "$PIN"
# After external host/status gates, choose ONE before the deadline:
python3 "$STATE/driver.py" commit "$TXN" --spec-sha256 "$PIN" --image-id sha256:REVIEWED_NEW_IMAGE_DIGEST
python3 "$STATE/driver.py" rollback "$TXN" --spec-sha256 "$PIN"
```

Rollback does **not** require an unexpired/active timer. It verifies immutable snapshots, pinned old identity/spec and Caddy hash, stops/removes only the candidate, renames the old container back if necessary, starts it and verifies its spec/running/health before finalizing and disarming. Candidate create/record and every cutover/rollback mutation have retry-idempotent recovery; unknown identities/configuration refuse destructive cleanup. It never edits Caddy, removes the durable volume or deletes/disconnects the network. Existing finalized schema-1 transactions remain immutable. A finalized schema-2 transaction is also a no-op on rollback, so do not use this as an after-commit downgrade command.

After commit the old stopped rollback container remains for manual review/retention; automatic cleanup is outside this procedure. Because both containers retain `unless-stopped`, do not manually start the rollback container while the new one is serving. A later update must pin the current live relay, not an earlier retained container. The driver cannot undo database schema changes; incompatible state migration requires a separately reviewed backup/restore plan.

Local fake tests cover round-trip refusal, full identity/name/alias restoration, failed gates, expired-deadline snapshot rollback, commit/disarm, old-spec drift, interrupted candidate creation/cutover/rollback, inherited flock, and both timer/commit orderings. These tests are not proof of live systemd persistence, production HTTP/WSS or physical-network acceptance.
