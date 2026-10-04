#!/usr/bin/env python3
"""Bounded first additive relay deployment. Linux/Python3 standard library only.

One persistent flock serializes arming, forward mutations, commit and rollback.
Immutable spec/bytes + write-ahead intents make interrupted operations recoverable.
No SSH, generic updater, shell sourcing, existing Compose edits or volume deletion.
"""
import argparse
import base64
import datetime as dt
import fcntl
import grp
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid

NETWORK = "dsh-mobile-relay-internal"
RELAY = "dsh-mobile-relay"
VOLUME = "dsh-mobile-relay-data"
TX_LABEL = "org.dsh-mobile-relay.transaction"
GUARD_LABEL = "org.dsh-mobile-relay.guard"
HEX = re.compile(r"[a-f0-9]{64}\Z")
TX = re.compile(r"[A-Za-z0-9_-]{1,48}\Z")
SAFE_PATH = re.compile(r"/[A-Za-z0-9_./-]+\Z")
HEALTH = "fetch('http://127.0.0.1:8088/healthz',{signal:AbortSignal.timeout(1500)}).then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD = ["serve", "--state", "/data/relay.sqlite", "--bind", "0.0.0.0", "--port", "8088", "--external-tls"]
ACTIVE = {"arming", "armed", "applying", "applied", "rollingback"}
SPEC_KEYS = {"schema", "txn", "home", "live", "original_sha256", "candidate_sha256", "live_mode", "caddy_id", "caddy_name", "caddy_path", "mount_source", "mount_dest", "baseline_networks", "image_id", "nonce", "deadline", "tools", "driver_sha256"}
STATE_KEYS = {"schema", "spec_sha256", "phase", "network", "relay", "attach_intent", "install_intent", "reload_verified", "restore_intent", "restore_verified"}
# Set only after strict HOME/owner validation by this single-command CLI.
PRIVATE_GROUP_PATHS = frozenset()


class Refusal(Exception):
    pass


def need(condition, message):
    if not condition:
        raise Refusal(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(Refusal("nonfinite JSON number")))
    except (ValueError, TypeError) as error:
        raise Refusal("invalid JSON") from error


def safe_path(value):
    need(isinstance(value, str) and SAFE_PATH.fullmatch(value) and all(p not in (".", "..") for p in value.split("/")), "unsafe absolute path (spaces, %, dot segments and shell escapes are unsupported): " + str(value))
    return Path(value)


def owner_only_group(gid):
    """NSS private primary group, not a blanket group-writable-path exception."""
    need(gid == os.getgid(), "path group is not the deployment primary group")
    try:
        user = pwd.getpwuid(os.getuid()).pw_name
        group = grp.getgrgid(gid)
        primary_members = pwd.getpwall()
        need(set(group.gr_mem) <= {user} and all(item.pw_uid == os.getuid() for item in primary_members if item.pw_gid == gid), "path group has another primary/supplementary member")
    except KeyError as error:
        raise Refusal("cannot prove private deployment group through NSS") from error


def check_chain(path):
    path = safe_path(str(path))
    for item in [*reversed(path.parents), path]:
        if item.exists() or item.is_symlink():
            info = item.lstat()
            need(not stat.S_ISLNK(info.st_mode), "symlink in protected path")
            private_group = item in PRIVATE_GROUP_PATHS and stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and info.st_mode & 0o020 and not info.st_mode & 0o002
            if private_group:
                owner_only_group(info.st_gid)
                need("system.posix_acl_access" not in os.listxattr(item, follow_symlinks=False) and "system.posix_acl_default" not in os.listxattr(item, follow_symlinks=False), "extended ACL on private-group path is unsupported")
            need(info.st_uid in (0, os.getuid()) and (not info.st_mode & 0o022 or private_group or info.st_uid == 0 and stat.S_ISDIR(info.st_mode) and info.st_mode & stat.S_ISVTX), "untrusted owner or writable path ancestor")
    return path


def private_dir(path):
    check_chain(path)
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    need(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700, "directory must be owner-only mode 0700")
    return path


def read_file(path, private=False, limit=2 * 1024 * 1024):
    check_chain(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        need(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_size <= limit, "unsafe file type, owner or size")
        if private:
            need(not info.st_mode & 0o077 and info.st_nlink == 1, "private file permissions/link count invalid")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            return stream.read(limit + 1)
    finally:
        os.close(fd)


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def exclusive(path, data, mode=0o400):
    check_chain(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    sync_dir(path.parent)


def replace_file(path, data, mode):
    check_chain(path)
    fd, name = tempfile.mkstemp(prefix=".relay-atomic-", dir=path.parent)
    stage = Path(name)
    try:
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(stage, path)
        sync_dir(path.parent)
    finally:
        stage.unlink(missing_ok=True)


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


class Driver:
    def __init__(self, home, mount_source=None):
        global PRIVATE_GROUP_PATHS
        PRIVATE_GROUP_PATHS = frozenset()
        need(os.getuid() != 0, "run as deployment owner, never root")
        self.home = check_chain(safe_path(home))
        need(self.home.is_dir() and self.home.stat().st_uid == os.getuid(), "HOME must be an existing owner directory")
        PRIVATE_GROUP_PATHS = frozenset(self.home / suffix for suffix in
                                        (".config", ".config/systemd", ".config/systemd/user", ".local"))
        # Recheck exactly these nonsecret ancestors on every invocation, including
        # immutable rollback; state/files/unknown siblings get no exception.
        for path in PRIVATE_GROUP_PATHS:
            check_chain(path)
        if mount_source is not None:
            self.site_paths(mount_source)
        for key, expected in (("XDG_STATE_HOME", self.home / ".local/state"), ("XDG_CONFIG_HOME", self.home / ".config")):
            need(not os.environ.get(key) or os.environ[key] == str(expected), "nondefault XDG state/config is unsupported; use the same HOME for SSH and user systemd")
        self.root = private_dir(self.home / ".local/state/dsh-mobile-relay")
        self.transactions = private_dir(self.root / "transactions")
        self.units = check_chain(self.home / ".config/systemd/user")
        self.units.mkdir(mode=0o700, parents=True, exist_ok=True)
        need(self.units.stat().st_uid == os.getuid(), "user unit directory must be owner-owned; never chmod unrelated units")
        check_chain(self.units)
        self.fd = os.open(self.root / "caddy.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        info = os.fstat(self.fd)
        need(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_nlink == 1 and not info.st_mode & 0o077, "unsafe global lock")
        # Finite lock budget. External commands inherit this fd: a parent-only
        # SIGKILL cannot release the transaction lock while its mutation child runs.
        until = time.monotonic() + 90
        while True:
            try:
                fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                need(time.monotonic() < until, "global lock budget exhausted; guard remains armed")
                time.sleep(0.05)
        self.spec = self.state = None
        self.tools = None
        self.operation_deadline = time.monotonic() + 180

    def site_paths(self, mount_source):
        """Allow only the configured direct HOME child, never arbitrary ancestors."""
        global PRIVATE_GROUP_PATHS
        source = safe_path(mount_source)
        need(source.is_relative_to(self.home), "mount source must be beneath deployment HOME")
        parts = source.relative_to(self.home).parts
        if len(parts) >= 2 and parts[0] not in (".config", ".local"):
            site = self.home / parts[0]
            PRIVATE_GROUP_PATHS = PRIVATE_GROUP_PATHS | {site}
            check_chain(site)

    def close(self):
        os.close(self.fd)

    def run(self, tool, *args, data=None, allow=False, timeout=20):
        remaining = self.operation_deadline - time.monotonic()
        need(remaining > 0, "180-second operation budget exhausted; guard remains armed")
        try:
            result = subprocess.run([self.tools[tool], *map(str, args)], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=min(timeout, remaining), check=False, pass_fds=(self.fd,), env={**os.environ, "HOME": str(self.home), "TZ": "UTC", "LC_ALL": "C"})
        except (OSError, subprocess.TimeoutExpired) as error:
            raise Refusal(tool + " command unavailable/timed out; inspect state before retry") from error
        need(allow or result.returncode == 0, tool + " command failed; details intentionally suppressed")
        return result

    def docker(self, *args, **kwargs):
        return self.run("docker", *args, **kwargs)

    def objects(self, *args):
        value = strict_json(self.docker(*args).stdout)
        need(isinstance(value, list) and len(value) == 1 and isinstance(value[0], dict), "expected one Docker inspect object")
        return value[0]

    def listed(self, kind):
        args = [kind, "ls"]
        if kind == "container":
            args.append("--all")
        if kind != "volume":
            args.append("--no-trunc")
        values = [strict_json(line) for line in self.docker(*args, "--format", "{{json .}}").stdout.splitlines() if line]
        need(all(isinstance(value, dict) for value in values), "invalid Docker resource list")
        return values

    def named(self, kind, name):
        key = "Names" if kind == "container" else "Name"
        values = [d for d in self.listed(kind) if d.get(key) == name]
        need(len(values) <= 1, "ambiguous Docker resource name")
        return values[0] if values else None

    def timer(self):
        return "dsh-mobile-relay-rollback-" + self.spec["txn"] + ".timer"

    def save(self):
        replace_file(self.dir / "state.json", encoded(self.state), 0o600)

    def load(self, txn, pin=None):
        need(TX.fullmatch(txn), "invalid transaction ID")
        self.dir = private_dir(self.transactions / txn)
        raw = read_file(self.dir / "spec.json", True)
        spec_hash = read_file(self.dir / "spec.sha256", True).decode().strip()
        need(HEX.fullmatch(spec_hash) and digest(raw) == spec_hash and (pin is None or pin == spec_hash), "immutable spec hash changed")
        s = strict_json(raw)
        need(isinstance(s, dict) and set(s) == SPEC_KEYS and s["schema"] == 1 and s["txn"] == txn and s["home"] == str(self.home), "invalid spec schema/identity")
        for key in ("original_sha256", "candidate_sha256", "caddy_id", "driver_sha256"):
            need(isinstance(s[key], str) and HEX.fullmatch(s[key]), "invalid spec digest/ID")
        for key in ("live", "mount_source", "mount_dest", "caddy_path"):
            safe_path(s[key])
        need(isinstance(s["caddy_name"], str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", s["caddy_name"]), "invalid pinned Caddy name")
        self.site_paths(s["mount_source"])
        need(isinstance(s["image_id"], str) and re.fullmatch(r"sha256:[a-f0-9]{64}", s["image_id"]), "invalid pinned image ID")
        need(type(s["deadline"]) is int and type(s["live_mode"]) is int and 0 <= s["live_mode"] <= 0o777 and re.fullmatch(r"[a-f0-9]{32}", s["nonce"]), "invalid spec deadline/mode/nonce")
        need(isinstance(s["baseline_networks"], dict) and NETWORK not in s["baseline_networks"] and all(isinstance(k, str) and isinstance(v, str) and HEX.fullmatch(v) for k, v in s["baseline_networks"].items()), "invalid baseline network map")
        need(isinstance(s["tools"], dict) and set(s["tools"]) == {"docker", "systemctl", "loginctl", "python"}, "invalid tool map")
        for value in s["tools"].values():
            check_chain(safe_path(value))
        self.spec, self.tools = s, s["tools"]
        state = strict_json(read_file(self.dir / "state.json", True, 16384))
        need(isinstance(state, dict) and set(state) == STATE_KEYS and state["schema"] == 1 and state["spec_sha256"] == spec_hash and state["phase"] in ACTIVE | {"committed", "rolledback"}, "invalid mutable state schema")
        for key in ("attach_intent", "install_intent", "reload_verified", "restore_intent", "restore_verified"):
            need(type(state[key]) is bool, "invalid intent boolean")
        for key in ("network", "relay"):
            item = state[key]
            need(isinstance(item, dict) and set(item) == {"intent", "id"} and type(item["intent"]) is bool and (item["id"] is None or isinstance(item["id"], str) and HEX.fullmatch(item["id"])) and (item["id"] is None or item["intent"]), "invalid resource intent/ID")
        self.state = state
        need(digest(read_file(self.dir / "driver.py", True)) == s["driver_sha256"], "rollback driver snapshot changed")
        self.verify_bytes()

    def verify_bytes(self):
        need(digest(read_file(self.dir / "original.Caddyfile", True)) == self.spec["original_sha256"], "arming-time backup hash changed")
        need(digest(read_file(self.dir / "candidate.Caddyfile", True)) == self.spec["candidate_sha256"], "arming-time candidate hash changed")

    def caddy(self):
        s = self.spec
        obj = self.objects("inspect", s["caddy_id"])
        need(obj.get("Id") == s["caddy_id"] and obj.get("Name") == "/" + s["caddy_name"] and obj.get("State", {}).get("Running") is True, "pinned Caddy identity/status changed")
        need(obj.get("Path") == "caddy" and obj.get("Args") == ["run", "--config", s["caddy_path"], "--adapter", "caddyfile"], "Caddy startup/config mapping changed")
        matches = [m for m in obj.get("Mounts", []) if m.get("Destination") == s["mount_dest"]]
        need(len(matches) == 1 and matches[0].get("Type") == "bind" and matches[0].get("Source") == s["mount_source"] and matches[0].get("RW") is False, "exact Caddy bind SOURCE/DEST/RW mapping changed")
        need(Path(s["live"]).parent == Path(s["mount_source"]) and Path(s["caddy_path"]).parent == Path(s["mount_dest"]) and Path(s["live"]).name == Path(s["caddy_path"]).name, "host/container Caddyfile mapping invalid")
        current = {key: value.get("NetworkID") for key, value in obj.get("NetworkSettings", {}).get("Networks", {}).items()}
        expected = dict(s["baseline_networks"])
        if NETWORK in current:
            need(self.state["attach_intent"] and self.state["network"]["id"] == current[NETWORK], "unowned Caddy attachment")
            expected[NETWORK] = self.state["network"]["id"]
        need(current == expected, "Caddy pre-existing network memberships changed")
        return obj

    def guard(self):
        need(self.state["phase"] in {"armed", "applying", "applied"}, "transaction not mutable")
        need(time.time() + 35 < self.spec["deadline"], "rollback deadline reached/too close for mutation")
        self.run("systemctl", "--user", "is-active", "--quiet", self.timer())
        next_time = self.run("systemctl", "--user", "show", self.timer(), "--property=NextElapseUSecRealtime", "--value").stdout.decode().strip()
        try:
            actual = dt.datetime.strptime(next_time, "%a %Y-%m-%d %H:%M:%S UTC").replace(tzinfo=dt.timezone.utc).timestamp()
        except ValueError as error:
            raise Refusal("cannot verify user timer UTC deadline") from error
        need(actual == self.spec["deadline"], "user timer deadline differs from armed deadline")
        self.verify_bytes()
        self.caddy()

    def resource(self, kind):
        record = self.state[kind]
        name = NETWORK if kind == "network" else RELAY
        item = self.named("network" if kind == "network" else "container", name)
        if item is None:
            return None
        need(record["intent"], "resource appeared without write-ahead creation intent")
        obj = self.objects("network", "inspect", name) if kind == "network" else self.objects("inspect", name)
        labels = obj.get("Labels", {}) if kind == "network" else obj.get("Config", {}).get("Labels", {})
        need(labels.get(TX_LABEL) == self.spec["txn"] and labels.get(GUARD_LABEL) == self.spec["nonce"], "resource ownership labels changed; unrelated resource untouched")
        identity = obj.get("Id")
        need(isinstance(identity, str) and HEX.fullmatch(identity) and (record["id"] is None or identity == record["id"]), "resource exact ID changed")
        if record["id"] is None:
            # Known absence at arm + durable intent + both labels prove a create/record crash.
            record["id"] = identity
            self.save()
        return obj

    def topology(self, full=False):
        network = self.resource("network")
        relay = self.resource("relay")
        caddy = self.caddy()
        if network is None:
            need(relay is None and NETWORK not in caddy["NetworkSettings"]["Networks"], "owned network missing with live endpoints")
            return None, None
        need(network.get("Name") == NETWORK and network.get("Internal") is True and network.get("Driver") == "bridge", "dedicated network is not internal bridge")
        allowed = {self.spec["caddy_id"]} if self.state["attach_intent"] else set()
        if relay:
            allowed.add(relay["Id"])
            networks = relay.get("NetworkSettings", {}).get("Networks", {})
            need(set(networks) == {NETWORK}, "relay must use only the dedicated network")
            actual_id = networks[NETWORK].get("NetworkID")
            # Engine leaves NetworkID empty for a never-started docker create.
            # Name + labels + exact container ID are pinned here; after start the
            # network ID must be exact. Rollback can remove an unstarted owned ID.
            unstarted = relay.get("State", {}).get("Status") == "created" and relay.get("State", {}).get("Running") is False
            need(actual_id == network["Id"] or unstarted and actual_id == "", "relay dedicated network ID changed")
            self.hardening(relay)
        endpoints = set(network.get("Containers") or {})
        need(endpoints <= allowed, "unexpected dedicated-network endpoint; refusing all cleanup")
        if full:
            need(relay is not None and relay.get("State", {}).get("Running") is True and relay.get("State", {}).get("Health", {}).get("Status") == "healthy", "relay not running/healthy")
            need(endpoints == {relay["Id"], self.spec["caddy_id"]} and NETWORK in caddy["NetworkSettings"]["Networks"], "expected exact relay/Caddy endpoint set")
        return network, relay

    def hardening(self, relay):
        cfg, host = relay.get("Config", {}), relay.get("HostConfig", {})
        need(relay.get("Image") == self.spec["image_id"] and cfg.get("User") == "10001:10001" and cfg.get("Entrypoint") == ["node", "dist/cli.js"] and cfg.get("Cmd") == CMD, "relay image/command/user differs from reviewed policy")
        need(host.get("ReadonlyRootfs") is True and host.get("Init") is True and host.get("Privileged") is False and set(host.get("CapDrop") or []) == {"ALL"} and not host.get("CapAdd") and not host.get("Devices") and "no-new-privileges:true" in (host.get("SecurityOpt") or []), "relay privilege hardening changed")
        need(host.get("Memory") == 536870912 and host.get("NanoCpus") == 500000000 and host.get("PidsLimit") == 128 and host.get("RestartPolicy", {}).get("Name") == "unless-stopped", "relay resource/restart limits changed")
        need(not host.get("PortBindings") and host.get("PublishAllPorts") is False and not relay.get("NetworkSettings", {}).get("Ports", {}).get("8088/tcp"), "relay has host-published ports")
        need(host.get("LogConfig") == {"Type": "json-file", "Config": {"max-size": "10m", "max-file": "3"}}, "relay log limits changed")
        need(host.get("Tmpfs") == {"/tmp": "rw,noexec,nosuid,nodev,size=16m,mode=1777"}, "relay tmpfs hardening changed")
        mounts = relay.get("Mounts", [])
        need(len(mounts) == 1 and mounts[0].get("Type") == "volume" and mounts[0].get("Name") == VOLUME and mounts[0].get("Destination") == "/data" and mounts[0].get("RW") is True, "relay durable mount changed")
        need(cfg.get("Healthcheck") == {"Test": ["CMD-SHELL", 'node -e "' + HEALTH + '"'], "Interval": 10000000000, "Timeout": 3000000000, "Retries": 3, "StartPeriod": 10000000000}, "relay healthcheck changed")

    def arm(self, args):
        need(TX.fullmatch(args.txn), "invalid transaction ID")
        for prior in self.transactions.iterdir():
            need(prior.is_dir() and not prior.is_symlink(), "unexpected transaction entry")
            raw = strict_json(read_file(prior / "state.json", True, 16384))
            need(raw.get("phase") in {"committed", "rolledback"}, "another transaction is active or incomplete; recover it first")
        self.dir = self.transactions / args.txn
        need(not self.dir.exists(), "transaction ID already used; never reuse finalized IDs")
        need(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", args.caddy_name), "invalid Caddy container name")
        self.site_paths(args.mount_source)
        live = check_chain(safe_path(args.live))
        candidate = check_chain(safe_path(args.candidate))
        source = check_chain(safe_path(args.mount_source))
        dest, cpath = safe_path(args.mount_dest), safe_path(args.caddy_path)
        need(live.is_relative_to(self.home) and source.is_relative_to(self.home) and live.parent == source and cpath.parent == dest and live.name == cpath.name and candidate != live, "Caddy file/mount mapping must be beneath deployment HOME")
        original, new = read_file(live), read_file(candidate)
        need(HEX.fullmatch(args.original_sha256) and digest(original) == args.original_sha256 and HEX.fullmatch(args.candidate_sha256) and digest(new) == args.candidate_sha256 and original != new, "operator-reviewed original/candidate hash mismatch")
        need(HEX.fullmatch(args.caddy_id) and re.fullmatch(r"sha256:[a-f0-9]{64}", args.image_id) and 60 <= args.delay <= 3600, "invalid image/container/deadline input")
        tools = {name: str(check_chain(Path(shutil.which(name) or "missing").resolve())) for name in ("docker", "systemctl", "loginctl")}
        tools["python"] = str(check_chain(Path(sys.executable).resolve()))
        self.tools = tools
        need(self.named("container", RELAY) is None and self.named("network", NETWORK) is None, "bounded first deployment requires relay/container network absent")
        need(args.reuse_data or self.named("volume", VOLUME) is None, "durable volume already exists; inspect it and explicitly approve --reuse-data")
        image = self.objects("image", "inspect", args.image_id)
        need(image.get("Id") == args.image_id and image.get("Config", {}).get("User") == "10001:10001" and image.get("Config", {}).get("Entrypoint") == ["node", "dist/cli.js"], "reviewed image identity/user/entrypoint mismatch")
        need(self.run("loginctl", "show-user", pwd.getpwuid(os.getuid()).pw_name, "--property=Linger", "--value").stdout.strip() == b"yes", "deployment user lingering is required")
        obj = self.objects("inspect", args.caddy_id)
        baseline = {k: v.get("NetworkID") for k, v in obj.get("NetworkSettings", {}).get("Networks", {}).items()}
        need(NETWORK not in baseline and all(isinstance(v, str) and HEX.fullmatch(v) for v in baseline.values()), "unsafe Caddy baseline network map")
        code = read_file(Path(__file__).resolve())
        self.spec = dict(schema=1, txn=args.txn, home=str(self.home), live=str(live), original_sha256=args.original_sha256, candidate_sha256=args.candidate_sha256, live_mode=stat.S_IMODE(live.stat().st_mode), caddy_id=args.caddy_id, caddy_name=args.caddy_name, caddy_path=str(cpath), mount_source=str(source), mount_dest=str(dest), baseline_networks=baseline, image_id=args.image_id, nonce=uuid.uuid4().hex, deadline=int(time.time()) + args.delay, tools=tools, driver_sha256=digest(code))
        self.state = dict(schema=1, spec_sha256=digest(encoded(self.spec)), phase="arming", network={"intent": False, "id": None}, relay={"intent": False, "id": None}, attach_intent=False, install_intent=False, reload_verified=False, restore_intent=False, restore_verified=False)
        self.caddy()
        # Candidate validation on stdin: no host path accidentally sent to docker exec.
        self.docker("exec", "-i", args.caddy_id, "caddy", "validate", "--config", "-", "--adapter", "caddyfile", data=new)
        private_dir(self.dir)
        for name, data in (("original.Caddyfile", original), ("candidate.Caddyfile", new), ("driver.py", code), ("spec.json", encoded(self.spec)), ("spec.sha256", (self.state["spec_sha256"] + "\n").encode())):
            exclusive(self.dir / name, data)
        exclusive(self.dir / "state.json", encoded(self.state), 0o600)
        service = self.timer().replace(".timer", ".service")
        deadline = dt.datetime.fromtimestamp(self.spec["deadline"], dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        exclusive(self.units / service, ("[Unit]\nDescription=DSH Mobile bounded deployment rollback\n[Service]\nType=oneshot\nEnvironment=TZ=UTC\nExecStart=" + tools["python"] + " " + str(self.dir / "driver.py") + " --home " + str(self.home) + " rollback " + args.txn + " --spec-sha256 " + self.state["spec_sha256"] + "\nTimeoutStartSec=300\nRestart=on-failure\nRestartSec=10s\n").encode(), 0o600)
        exclusive(self.units / self.timer(), ("[Unit]\nDescription=DSH Mobile independent rollback deadline\n[Timer]\nOnCalendar=" + deadline + "\nAccuracySec=1s\nRandomizedDelaySec=0\nPersistent=true\nUnit=" + service + "\n[Install]\nWantedBy=timers.target\n").encode(), 0o600)
        self.run("systemctl", "--user", "daemon-reload")
        self.run("systemctl", "--user", "enable", "--now", self.timer())
        self.state["phase"] = "armed"
        self.save()
        self.guard()
        print("Armed " + args.txn + "; pinned bytes, image and UTC deadline; no production resources changed")

    def apply(self):
        need(self.state["phase"] == "armed", "apply is single-shot; interrupted/applying transactions must rollback, not resume forward")
        self.guard()
        need(digest(read_file(Path(self.spec["live"]))) == self.spec["original_sha256"], "live Caddyfile no longer original")
        self.state["phase"] = "applying"
        self.state["network"]["intent"] = True
        self.save()
        self.guard()
        need(self.named("network", NETWORK) is None, "network appeared concurrently")
        self.docker("network", "create", "--driver", "bridge", "--internal", "--label", TX_LABEL + "=" + self.spec["txn"], "--label", GUARD_LABEL + "=" + self.spec["nonce"], NETWORK)
        network = self.resource("network")
        need(network is not None, "network create did not produce owned resource")
        self.state["relay"]["intent"] = True
        self.save()
        self.guard()
        need(self.named("container", RELAY) is None, "relay appeared concurrently")
        self.docker("create", "--name", RELAY, "--label", TX_LABEL + "=" + self.spec["txn"], "--label", GUARD_LABEL + "=" + self.spec["nonce"], "--network", network["Id"], "--network-alias", RELAY, "--init", "--user", "10001:10001", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--memory", "512m", "--cpus", "0.5", "--pids-limit", "128", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777", "--mount", "type=volume,source=" + VOLUME + ",target=/data", "--health-cmd", 'node -e "' + HEALTH + '"', "--health-interval", "10s", "--health-timeout", "3s", "--health-retries", "3", "--health-start-period", "10s", "--restart", "unless-stopped", "--log-driver", "json-file", "--log-opt", "max-size=10m", "--log-opt", "max-file=3", self.spec["image_id"], *CMD)
        _, relay = self.topology()
        need(relay is not None, "relay create did not produce owned resource")
        self.guard()
        self.docker("start", relay["Id"])
        until = time.monotonic() + 25
        while True:
            _, relay = self.topology()
            if relay.get("State", {}).get("Health", {}).get("Status") == "healthy":
                break
            need(time.monotonic() < until, "relay did not become healthy within 25s")
            time.sleep(0.25)
        self.guard()
        self.state["attach_intent"] = True
        self.save()
        self.docker("network", "connect", network["Id"], self.spec["caddy_id"])
        self.topology(full=True)
        self.guard()
        self.state["install_intent"] = True
        self.save()
        candidate = read_file(self.dir / "candidate.Caddyfile", True)
        self.docker("exec", "-i", self.spec["caddy_id"], "caddy", "validate", "--config", "-", "--adapter", "caddyfile", data=candidate)
        self.guard()
        need(digest(read_file(Path(self.spec["live"]))) == self.spec["original_sha256"], "live changed before guarded candidate install")
        replace_file(Path(self.spec["live"]), candidate, self.spec["live_mode"])
        self.reload(self.spec["candidate_sha256"])
        self.state["reload_verified"] = True
        self.state["phase"] = "applied"
        self.save()
        print("Applied; rollback remains armed. Operator must check public canary and original services before explicit commit")

    def reload(self, expected):
        self.caddy()
        need(digest(read_file(Path(self.spec["live"]))) == expected, "host Caddyfile changed before reload")
        inside = self.docker("exec", self.spec["caddy_id"], "sha256sum", self.spec["caddy_path"]).stdout.decode().split()
        need(inside and inside[0] == expected, "mounted container Caddyfile hash differs")
        self.docker("exec", self.spec["caddy_id"], "caddy", "reload", "--config", self.spec["caddy_path"], "--adapter", "caddyfile")
        self.caddy()
        need(digest(read_file(Path(self.spec["live"]))) == expected, "Caddyfile changed after reload")

    def disarm(self):
        self.run("systemctl", "--user", "disable", "--now", self.timer())

    def commit(self, expected):
        need(expected == self.spec["candidate_sha256"], "commit requires exact armed candidate SHA-256")
        if self.state["phase"] == "committed":
            self.disarm()
            print("Already committed; timer disarmed")
            return
        need(self.state["phase"] == "applied" and self.state["reload_verified"], "only verified applied transaction can commit")
        self.guard()
        self.topology(full=True)
        need(digest(read_file(Path(self.spec["live"]))) == expected, "commit live hash differs")
        # Linearization point under the same flock as the timer. A queued timer that
        # starts afterward observes committed and cannot restore or detach anything.
        need(time.time() < self.spec["deadline"], "deadline won commit race")
        self.state["phase"] = "committed"
        self.save()
        self.disarm()
        print("Committed explicitly; queued rollback is now a no-op")

    def rollback(self):
        if self.state["phase"] in {"committed", "rolledback"}:
            self.disarm()
            print("Finalized transaction; no Caddy or Docker mutations")
            return
        self.verify_bytes()
        live = Path(self.spec["live"])
        current = digest(read_file(live))
        need(current in (self.spec["original_sha256"], self.spec["candidate_sha256"]), "unknown live Caddyfile hash; refusing restore AND all resource cleanup")
        self.caddy()
        network, relay = self.topology()
        self.state["phase"] = "rollingback"
        self.save()
        if current == self.spec["candidate_sha256"]:
            self.state["restore_intent"] = True
            self.save()
            replace_file(live, read_file(self.dir / "original.Caddyfile", True), self.spec["live_mode"])
        if (self.state["restore_intent"] or self.state["reload_verified"]) and not self.state["restore_verified"]:
            # Original bytes may follow a crash between restore and reload/record.
            self.reload(self.spec["original_sha256"])
            self.state["restore_verified"] = True
            self.save()
        # Stage-before-file-change needs no reload. Validate *all* identities and
        # topology first, so unknown external content/resources remain untouched.
        need(digest(read_file(live)) == self.spec["original_sha256"], "original hash lost before cleanup")
        network, relay = self.topology()
        if relay:
            if relay.get("State", {}).get("Running") is True:
                self.docker("stop", "--time", "10", relay["Id"], timeout=20)
            exact = self.objects("inspect", relay["Id"])
            need(exact.get("Id") == relay["Id"] and exact.get("State", {}).get("Running") is False, "owned relay did not stop; refusing removal")
            self.docker("rm", relay["Id"])
        network, _ = self.topology()
        if network and self.state["attach_intent"] and NETWORK in self.caddy()["NetworkSettings"]["Networks"]:
            self.docker("network", "disconnect", network["Id"], self.spec["caddy_id"])
        network, _ = self.topology()
        if network:
            need(not network.get("Containers"), "network not empty; refusing removal")
            self.docker("network", "rm", network["Id"])
        self.state["phase"] = "rolledback"
        self.save()
        self.disarm()
        print("Rolled back; original config verified; only owned resources removed; durable volume retained")

    def provision(self, output):
        need(self.state["phase"] in {"applied", "committed"}, "route provisioning requires applied or committed transaction")
        if self.state["phase"] == "applied":
            self.guard()
        _, relay = self.topology(full=True)
        output = check_chain(safe_path(output))
        need(output.is_relative_to(self.home) and not output.exists() and not output.is_symlink(), "output must be new beneath owner HOME")
        private_dir(output.parent)
        operation = uuid.uuid4().hex
        recovery = private_dir(self.dir / ("route-" + operation))
        stage = "/data/.route-capability-" + operation + ".json"
        transfer = recovery / "capability.json"
        note = {"container_id": relay["Id"], "container_stage": stage, "host_output": str(output), "route_id": None, "phase": "intent"}
        exclusive(recovery / "recovery.json", encoded(note), 0o600)
        try:
            self.docker("exec", relay["Id"], "node", "dist/cli.js", "provision", "--state", "/data/relay.sqlite", "--output", stage)
            self.docker("cp", relay["Id"] + ":" + stage, str(transfer))
            os.chmod(transfer, 0o600, follow_symlinks=False)
            raw = read_file(transfer, True, 2048)
            value = strict_json(raw)
            need(isinstance(value, dict) and set(value) == {"routeId", "connectorToken"} and isinstance(value["routeId"], str) and re.fullmatch(r"[a-f0-9]{32}", value["routeId"]) and isinstance(value["connectorToken"], str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", value["connectorToken"]), "invalid private capability shape")
            token = value["connectorToken"]
            need(base64.urlsafe_b64encode(base64.urlsafe_b64decode(token + "=")).decode().rstrip("=") == token, "noncanonical capability token")
            note.update(route_id=value["routeId"], phase="copied")
            replace_file(recovery / "recovery.json", encoded(note), 0o600)
            with open(transfer, "rb") as stream:
                os.fsync(stream.fileno())
            # link is atomic and fails if a competing output exists; unlike mv -n
            # it cannot report success while leaving a different destination.
            os.link(transfer, output, follow_symlinks=False)
            sync_dir(output.parent)
            transfer.unlink()
            self.docker("exec", relay["Id"], "node", "-e", "require('node:fs').unlinkSync(process.argv[1])", stage)
            note["phase"] = "published"
            replace_file(recovery / "recovery.json", encoded(note), 0o600)
            print("Private route capability published; sha256=" + digest(raw) + "; no secret emitted")
        except (Refusal, OSError):
            print("Route may exist: preserve exact private staging/recovery files; inspect/revoke before any retry. Recovery: " + str(recovery / "recovery.json"), file=sys.stderr)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", default=os.environ.get("HOME", ""))
    sub = parser.add_subparsers(dest="command", required=True)
    arm = sub.add_parser("arm")
    arm.add_argument("txn")
    for name in ("live", "candidate", "original-sha256", "candidate-sha256", "caddy-id", "caddy-name", "mount-source", "mount-dest", "caddy-path", "image-id"):
        arm.add_argument("--" + name, required=True)
    arm.add_argument("--delay", type=int, default=900)
    arm.add_argument("--reuse-data", action="store_true")
    for name in ("apply", "rollback", "commit", "check", "provision"):
        command = sub.add_parser(name)
        command.add_argument("txn")
        command.add_argument("--spec-sha256")
        if name == "commit":
            command.add_argument("--candidate-sha256", required=True)
        if name == "provision":
            command.add_argument("--output", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    driver = None
    try:
        driver = Driver(args.home, args.mount_source if args.command == 'arm' else None)
        if args.command == "arm":
            driver.arm(args)
        else:
            driver.load(args.txn, args.spec_sha256)
            if args.command == "commit":
                driver.commit(args.candidate_sha256)
            elif args.command == "provision":
                driver.provision(args.output)
            elif args.command == "check":
                driver.topology(full=True)
                print("Exact topology and reviewed hardening verified; phase=" + driver.state["phase"])
            else:
                getattr(driver, args.command)()
        return 0
    except (Refusal, OSError, KeyError, TypeError) as error:
        print("relay-transaction refused: " + (str(error) if isinstance(error, Refusal) else "invalid/unavailable local state (" + type(error).__name__ + (" errno=" + str(error.errno) if isinstance(error, OSError) else " field=" + str(error) if isinstance(error, KeyError) else "") + "); guard retained"), file=sys.stderr)
        return 1
    finally:
        if driver:
            driver.close()


if __name__ == "__main__":
    sys.exit(main())
