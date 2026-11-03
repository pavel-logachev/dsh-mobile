#!/usr/bin/env python3
"""Local CLI tests: fake external commands, real files/processes/POSIX flock."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parent
CADDY = "a" * 64
IMAGE = "sha256:" + "d" * 64
BASE_NETWORK = "b" * 64


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="relay-guard-")
        self.root = Path(self.temp.name)
        self.home = self.root / "home"
        self.home.mkdir(mode=0o700)
        self.mount = self.home / "caddy"
        self.mount.mkdir(mode=0o700)
        self.live = self.mount / "Caddyfile"
        self.live.write_bytes(b"original configuration\n")
        self.candidate = self.home / "candidate"
        self.candidate.write_bytes(b"additive relay candidate\n")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("docker", "systemctl", "loginctl", "curl"):
            script = self.bin / name
            script.write_text("#!" + sys.executable + "\n" + (ROOT / "test_fake_commands.py").read_text())
            script.chmod(0o700)
        self.fake = self.root / "fake.json"
        self.fake.write_text(json.dumps({
            "containers": {CADDY: {
                "Id": CADDY, "Name": "/example-site-caddy", "Path": "caddy",
                "Args": ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"],
                "Mounts": [{"Type": "bind", "Source": str(self.mount), "Destination": "/etc/caddy", "RW": False}],
                "State": {"Running": True, "Status": "running"},
                "NetworkSettings": {"Networks": {"site-default": {"NetworkID": BASE_NETWORK}}},
                "Config": {"Labels": {}}, "HostConfig": {},
            }}, "networks": {}, "volumes": {}, "timers": {}, "calls": [], "fail": None,
        }))
        self.env = dict(os.environ, HOME=str(self.home), PATH=str(self.bin) + ":" + os.environ["PATH"], FAKE_STATE=str(self.fake), PYTHONDONTWRITEBYTECODE="1")
        for name in ("XDG_STATE_HOME", "XDG_CONFIG_HOME", "XDG_RUNTIME_DIR"):
            self.env.pop(name, None)
        self.txndir = self.home / ".local/state/dsh-mobile-relay/transactions/t1"

    def tearDown(self):
        self.temp.cleanup()

    def run_cli(self, *args, ok=True):
        result = subprocess.run([sys.executable, str(ROOT / "transaction.py"), *args], env=self.env, capture_output=True, text=True, timeout=30)
        if ok:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def arm(self, txn="t1", **extra):
        return self.run_cli("arm", txn, "--live", str(self.live), "--candidate", str(self.candidate),
                            "--original-sha256", hashlib.sha256(self.live.read_bytes()).hexdigest(),
                            "--candidate-sha256", hashlib.sha256(self.candidate.read_bytes()).hexdigest(),
                            "--caddy-id", CADDY, "--caddy-name", "example-site-caddy", "--mount-source", str(self.mount),
                            "--mount-dest", "/etc/caddy", "--caddy-path", "/etc/caddy/Caddyfile",
                            "--image-id", IMAGE, "--delay", "900", **extra)

    def state(self):
        return json.loads((self.txndir / "state.json").read_text())

    def fake_state(self):
        return json.loads(self.fake.read_text())

    def change_fake(self, change):
        data = self.fake_state()
        change(data)
        self.fake.write_text(json.dumps(data))

    def seed_existing_relay(self):
        self.arm("initial")
        self.run_cli("apply", "initial")
        self.run_cli("commit", "initial", "--candidate-sha256", hashlib.sha256(self.candidate.read_bytes()).hexdigest())
        self.old_id = "e" * 64
        return self.fake_state()["containers"][self.old_id]

    def update(self, **extra):
        return self.run_cli("update", "t1", "--live", str(self.live),
                            "--caddy-id", CADDY, "--caddy-name", "example-site-caddy",
                            "--mount-source", str(self.mount), "--mount-dest", "/etc/caddy",
                            "--caddy-path", "/etc/caddy/Caddyfile", "--old-id", self.old_id,
                            "--image-id", "sha256:" + "f" * 64,
                            "--website-url", "https://example.test/",
                            "--relay-health-url", "https://example.test/dsh-mobile-relay/healthz", **extra)

    def test_update_roundtrip_then_rollback_preserves_exact_old_identity_name_alias(self):
        original = self.seed_existing_relay()
        result = self.update()
        self.assertIn("restore spec verified", result.stdout)
        self.assertEqual(self.state()["phase"], "armed")
        self.assertEqual(self.fake_state()["containers"][self.old_id], original)
        self.run_cli("cutover", "t1")
        self.assertEqual(self.fake_state()["containers"][self.old_id]["Name"], "/dsh-mobile-relay-rollback-t1")
        self.run_cli("rollback", "t1")
        old = self.fake_state()["containers"][self.old_id]
        self.assertEqual(old["Name"], "/dsh-mobile-relay")
        self.assertTrue(old["State"]["Running"])
        self.assertEqual(old["NetworkSettings"]["Networks"], original["NetworkSettings"]["Networks"])
        self.assertEqual(old["Config"], original["Config"])
        self.assertEqual(old["HostConfig"], original["HostConfig"])
        self.assertEqual(set(self.fake_state()["containers"]), {CADDY, self.old_id})
        self.assertIn("dsh-mobile-relay-data", self.fake_state()["volumes"])
        self.assertEqual(self.live.read_bytes(), self.candidate.read_bytes())

    def test_update_refuses_non_roundtripping_spec_before_stopping_old(self):
        self.seed_existing_relay()
        self.change_fake(lambda d: d["containers"][self.old_id]["HostConfig"].update(ShmSize=123456))
        self.update(ok=False)
        self.assertTrue(self.fake_state()["containers"][self.old_id]["State"]["Running"])
        self.assertFalse(any(c[:1] == ["stop"] for c in self.fake_state()["calls"]))
        self.assertEqual(self.state()["phase"], "rolledback")
        self.assertEqual(set(self.fake_state()["containers"]), {CADDY, self.old_id})

    def test_update_failed_roundtrip_cleanup_can_retry_after_remove_failure(self):
        self.seed_existing_relay()
        self.change_fake(lambda d: d["containers"][self.old_id]["HostConfig"].update(ShmSize=123456))
        self.change_fake(lambda d: d.update(fail=["rm"]))
        self.update(ok=False)
        self.assertEqual(self.state()["phase"], "rollingback")
        self.change_fake(lambda d: d.update(fail=None))
        self.run_cli("rollback", "t1")
        self.assertEqual(self.state()["phase"], "rolledback")
        self.assertEqual(set(self.fake_state()["containers"]), {CADDY, self.old_id})

    def test_update_gates_commit_disarms_and_keeps_stopped_rollback(self):
        self.seed_existing_relay()
        self.update()
        result = self.run_cli("cutover", "t1")
        self.assertIn("Cutover UTC:", result.stdout)
        self.assertTrue(self.state()["gates_passed"])
        self.run_cli("commit", "t1", "--image-id", "sha256:" + "f" * 64)
        self.assertEqual(self.state()["phase"], "committed")
        self.assertFalse(self.fake_state()["timers"]["dsh-mobile-relay-rollback-t1.timer"])
        self.assertFalse(self.fake_state()["containers"][self.old_id]["State"]["Running"])
        self.run_cli("rollback", "t1")
        self.assertEqual(self.fake_state()["containers"]["9" * 64]["Name"], "/dsh-mobile-relay")

    def test_update_failed_http_gate_rolls_back_immediately(self):
        self.seed_existing_relay()
        self.update()
        self.change_fake(lambda d: d.update(http_status=503))
        self.run_cli("cutover", "t1", ok=False)
        self.assertEqual(self.state()["phase"], "rolledback")
        self.assertEqual(self.fake_state()["containers"][self.old_id]["Name"], "/dsh-mobile-relay")
        self.assertTrue(self.fake_state()["containers"][self.old_id]["State"]["Running"])

    def test_update_pinned_old_changed_refuses_cutover_without_stopping_it(self):
        self.seed_existing_relay()
        self.update()
        self.change_fake(lambda d: d["containers"][self.old_id]["HostConfig"].update(Memory=123))
        result = self.run_cli("cutover", "t1", ok=False)
        self.assertIn("pinned old relay", result.stderr)
        self.assertTrue(self.fake_state()["containers"][self.old_id]["State"]["Running"])
        self.assertFalse(any(c[:1] == ["stop"] for c in self.fake_state()["calls"]))

    def test_update_timer_snapshot_restores_after_cutover(self):
        self.seed_existing_relay()
        self.update()
        self.run_cli("cutover", "t1")
        unit = (self.home / ".config/systemd/user/dsh-mobile-relay-rollback-t1.service").read_text()
        self.assertIn(str(self.txndir / "driver.py"), unit)
        self.assertIn("--spec-sha256 " + self.state()["spec_sha256"], unit)
        # Execute the exact service ExecStart at its recorded timer deadline.
        # Fake systemd has no wall clock; rollback itself must not require an
        # active/unexpired timer (including a missed persistent reboot deadline).
        import shlex
        command = shlex.split(next(line.split("=", 1)[1] for line in unit.splitlines() if line.startswith("ExecStart=")))
        spec = json.loads((self.txndir / "spec.json").read_text())
        harness = self.root / "expire.py"
        harness.write_text("import runpy,sys,time\ntime.time=lambda: " + str(spec["deadline"] + 1) + "\nsys.argv=" + repr(command[1:]) + "\nrunpy.run_path(sys.argv[0],run_name='__main__')\n")
        result = subprocess.run([sys.executable, str(harness)], env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.state()["phase"], "rolledback")
        self.assertEqual(self.fake_state()["containers"][self.old_id]["Name"], "/dsh-mobile-relay")

    def test_update_cutover_and_rollback_crash_windows_are_retry_safe(self):
        for action, cases in (("cutover", [["stop"], ["rename", "e" * 64], ["rename", "9" * 64], ["start", "9" * 64]]),
                              ("rollback", [["stop", "9" * 64], ["rm", "9" * 64], ["rename", "e" * 64], ["start", "e" * 64]])):
            for contains in cases:
                for when in ("before", "after"):
                    with self.subTest(action=action, command=contains, window=when):
                        self.tearDown()
                        self.setUp()
                        self.seed_existing_relay()
                        self.update()
                        if action == "rollback":
                            self.run_cli("cutover", "t1")
                        proc, _ = self.spawn_barrier([action, "t1"], contains, when)
                        self.kill_crash(proc)
                        self.run_cli("rollback", "t1")
                        old = self.fake_state()["containers"][self.old_id]
                        self.assertEqual(old["Name"], "/dsh-mobile-relay")
                        self.assertTrue(old["State"]["Running"])
                        self.assertEqual(set(self.fake_state()["containers"]), {CADDY, self.old_id})
                        self.assertEqual(self.state()["phase"], "rolledback")

    def test_update_real_flock_orders_commit_and_timer_both_ways(self):
        for winner in ("commit", "rollback"):
            with self.subTest(winner=winner):
                self.tearDown()
                self.setUp()
                self.seed_existing_relay()
                self.update()
                self.run_cli("cutover", "t1")
                commit = ["commit", "t1", "--image-id", "sha256:" + "f" * 64]
                first = commit if winner == "commit" else ["rollback", "t1"]
                proc, release = self.spawn_barrier(first, ["systemctl", "show"] if winner == "commit" else ["stop"])
                other = ["rollback", "t1"] if winner == "commit" else commit
                loser = subprocess.Popen([sys.executable, str(ROOT / "transaction.py"), *other], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    time.sleep(0.2)
                    self.assertIsNone(loser.poll())
                    release.touch()
                    out, err = proc.communicate(timeout=15)
                    self.assertEqual(proc.returncode, 0, out + err)
                    out, err = loser.communicate(timeout=15)
                    self.assertEqual(loser.returncode, 0 if winner == "commit" else 1, out + err)
                    self.assertEqual(self.state()["phase"], "committed" if winner == "commit" else "rolledback")
                finally:
                    release.touch()
                    for child in (proc, loser):
                        if child.poll() is None:
                            child.kill()
                            child.communicate()

    def test_update_create_crash_and_parent_only_kill_are_recoverable(self):
        for when in ("before", "after"):
            for parent_only in (False, True):
                with self.subTest(window=when, parent_only=parent_only):
                    self.tearDown()
                    self.setUp()
                    self.seed_existing_relay()
                    args = ["update", "t1", "--live", str(self.live), "--caddy-id", CADDY,
                            "--caddy-name", "example-site-caddy", "--mount-source", str(self.mount),
                            "--mount-dest", "/etc/caddy", "--caddy-path", "/etc/caddy/Caddyfile",
                            "--old-id", self.old_id, "--image-id", "sha256:" + "f" * 64,
                            "--website-url", "https://example.test/", "--relay-health-url", "https://example.test/healthz"]
                    proc, release = self.spawn_barrier(args, ["create"], when)
                    if not parent_only:
                        self.kill_crash(proc)
                        self.run_cli("rollback", "t1")
                    else:
                        proc.kill()
                        proc.wait(timeout=5)
                        watcher = subprocess.Popen([sys.executable, str(ROOT / "transaction.py"), "rollback", "t1"], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                        try:
                            time.sleep(0.2)
                            self.assertIsNone(watcher.poll())
                            release.touch()
                            proc.communicate(timeout=10)
                            out, err = watcher.communicate(timeout=15)
                            self.assertEqual(watcher.returncode, 0, out + err)
                        finally:
                            release.touch()
                            if watcher.poll() is None:
                                watcher.kill()
                                watcher.communicate()
                    self.assertEqual(self.state()["phase"], "rolledback")
                    self.assertEqual(set(self.fake_state()["containers"]), {CADDY, self.old_id})
                    self.assertTrue(self.fake_state()["containers"][self.old_id]["State"]["Running"])

    def test_update_health_other_ids_and_deadline_failures_restore_old(self):
        for cause in ("health", "other-id", "timer"):
            with self.subTest(cause=cause):
                self.tearDown()
                self.setUp()
                self.seed_existing_relay()
                self.update()
                if cause == "health":
                    self.change_fake(lambda d: d.update(start_health="unhealthy"))
                    self.run_cli("cutover", "t1", ok=False)
                elif cause == "other-id":
                    self.change_fake(lambda d: d["containers"].update({"8" * 64: {"Id": "8" * 64, "Name": "/unrelated"}}))
                    self.run_cli("cutover", "t1", ok=False)
                else:
                    self.run_cli("cutover", "t1")
                    self.change_fake(lambda d: d["timers"].update({"dsh-mobile-relay-rollback-t1.timer": False}))
                    self.run_cli("commit", "t1", "--image-id", "sha256:" + "f" * 64, ok=False)
                self.assertEqual(self.state()["phase"], "rolledback")
                self.assertTrue(self.fake_state()["containers"][self.old_id]["State"]["Running"])

    def test_update_rejects_bad_cli_inputs_without_container_mutation(self):
        self.seed_existing_relay()
        base = ["update", "t1", "--live", str(self.live), "--caddy-id", CADDY,
                "--caddy-name", "example-site-caddy", "--mount-source", str(self.mount),
                "--mount-dest", "/etc/caddy", "--caddy-path", "/etc/caddy/Caddyfile",
                "--old-id", self.old_id, "--image-id", "sha256:" + "f" * 64,
                "--website-url", "https://example.test/", "--relay-health-url", "https://example.test/healthz"]
        cases = [("--image-id", ""), ("--image-id", "sha256:" + "f" * 63),
                 ("--old-id", "e" * 64 + "\n"), ("--website-url", "http://example.test/"),
                 ("--website-url", "https://example.test/?secret=value"),
                 ("--website-url", "https://name:pass@example.test/"),
                 ("--live", str(self.home / "../caddy/Caddyfile"))]
        for flag, value in cases:
            with self.subTest(flag=flag, value=value):
                args = list(base)
                args[args.index(flag) + 1] = value
                before = self.fake_state()["containers"]
                self.run_cli(*args, ok=False)
                self.assertEqual(self.fake_state()["containers"], before)
        for delay in ("0", "-1", "999999999999999999999", "１", "600\r"):
            self.run_cli(*base, "--delay", delay, ok=False)
        for txn in ("", "../x", "t1\n", "тест", "x" * 49, "\ufefft1"):
            args = list(base)
            args[1] = txn
            self.run_cli(*args, ok=False)
        self.assertFalse(self.txndir.exists())

    def test_update_caddy_drift_retains_guard_and_never_clobbers_file(self):
        self.seed_existing_relay()
        self.update()
        self.live.write_bytes(b"unrelated change\n")
        self.run_cli("cutover", "t1", ok=False)
        self.assertTrue(self.fake_state()["containers"][self.old_id]["State"]["Running"])
        self.assertEqual(self.live.read_bytes(), b"unrelated change\n")
        self.assertTrue(self.fake_state()["timers"]["dsh-mobile-relay-rollback-t1.timer"])
        self.live.write_bytes(self.candidate.read_bytes())
        self.run_cli("rollback", "t1")

    def test_update_wrong_spec_pin_and_corrupt_state_never_trigger_cleanup(self):
        self.seed_existing_relay()
        self.update()
        before = self.fake_state()["containers"]
        self.run_cli("cutover", "t1", "--spec-sha256", "0" * 64, ok=False)
        self.assertEqual(self.fake_state()["containers"], before)
        self.assertEqual(self.state()["phase"], "armed")
        original = (self.txndir / "state.json").read_bytes()
        changed = self.state()
        changed["schema"] = 99
        (self.txndir / "state.json").write_text(json.dumps(changed))
        self.run_cli("cutover", "t1", ok=False)
        self.assertEqual(self.fake_state()["containers"], before)
        (self.txndir / "state.json").write_bytes(original)
        self.run_cli("rollback", "t1")

    def test_new_cutover_command_refuses_schema_one_without_mutation(self):
        self.arm()
        result = self.run_cli("cutover", "t1", ok=False)
        self.assertIn("unsupported for first deployment", result.stderr)
        self.assertEqual(self.state()["phase"], "armed")
        self.assertEqual(self.fake_state()["networks"], {})

    def test_arm_pins_bytes_and_second_active_transaction_is_refused(self):
        self.arm()
        self.assertEqual(self.state()["phase"], "armed")
        self.assertEqual((self.txndir / "original.Caddyfile").read_bytes(), b"original configuration\n")
        self.arm("t2", ok=False)
        self.run_cli("rollback", "t1")
        self.assertEqual(self.state()["phase"], "rolledback")
        self.assertEqual(self.live.read_bytes(), b"original configuration\n")
        self.assertFalse(any(call[:1] == ["exec"] and "reload" in call for call in self.fake_state()["calls"]))

    def test_apply_commit_and_finalized_rollback_cannot_undo_commit(self):
        self.arm()
        self.run_cli("apply", "t1")
        self.assertEqual(self.state()["phase"], "applied")
        self.assertEqual(self.live.read_bytes(), self.candidate.read_bytes())
        self.run_cli("check", "t1")
        self.run_cli("commit", "t1", "--candidate-sha256", hashlib.sha256(self.candidate.read_bytes()).hexdigest())
        self.run_cli("rollback", "t1")
        self.assertEqual(self.state()["phase"], "committed")
        self.assertEqual(self.live.read_bytes(), self.candidate.read_bytes())
        self.assertIn("dsh-mobile-relay-internal", self.fake_state()["networks"])

    def test_candidate_rollback_stops_exact_owned_container_and_preserves_volume(self):
        self.arm()
        self.run_cli("apply", "t1")
        self.run_cli("rollback", "t1")
        data = self.fake_state()
        self.assertEqual(self.live.read_bytes(), b"original configuration\n")
        self.assertEqual(self.state()["phase"], "rolledback")
        self.assertEqual(set(data["containers"]), {CADDY})
        self.assertEqual(data["networks"], {})
        self.assertIn("dsh-mobile-relay-data", data["volumes"])
        self.assertIn(["stop", "--time", "10", "e" * 64], data["calls"])
        self.assertIn(["rm", "e" * 64], data["calls"])
        self.run_cli("rollback", "t1")
        self.run_cli("apply", "t1", ok=False)

    def test_unknown_live_hash_never_restores_detaches_or_removes(self):
        self.arm()
        self.run_cli("apply", "t1")
        self.live.write_bytes(b"external unrelated edit\n")
        before = self.fake_state()
        self.run_cli("rollback", "t1", ok=False)
        after = self.fake_state()
        self.assertEqual(after["containers"], before["containers"])
        self.assertEqual(after["networks"], before["networks"])
        self.assertEqual(self.live.read_bytes(), b"external unrelated edit\n")

    def test_immutable_backup_candidate_and_duplicate_json_are_refused(self):
        self.arm()
        backup = self.txndir / "original.Caddyfile"
        backup.chmod(0o600)
        backup.write_bytes(b"mutated backup\n")
        self.run_cli("apply", "t1", ok=False)
        self.run_cli("rollback", "t1", ok=False)
        self.assertEqual(self.live.read_bytes(), b"original configuration\n")
        backup.write_bytes(b"original configuration\n")
        candidate = self.txndir / "candidate.Caddyfile"
        candidate.chmod(0o600)
        candidate.write_bytes(b"mutated candidate\n")
        self.run_cli("apply", "t1", ok=False)
        candidate.write_bytes(b"additive relay candidate\n")
        raw = (self.txndir / "state.json").read_text()
        (self.txndir / "state.json").write_text('{"phase":"committed",' + raw[1:])
        self.run_cli("rollback", "t1", ok=False)

    def spawn_barrier(self, command, contains, when="after"):
        barrier = self.root / ("barrier-" + str(time.time_ns()) + ".json")
        ready = barrier.with_suffix(".ready")
        release = barrier.with_suffix(".release")
        barrier.write_text(json.dumps({"contains": contains, "when": when, "ready": str(ready), "release": str(release)}))
        env = dict(self.env, FAKE_BARRIER=str(barrier))
        proc = subprocess.Popen([sys.executable, str(ROOT / "transaction.py"), *command], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        until = time.monotonic() + 15
        while not ready.exists():
            if proc.poll() is not None:
                self.fail("barrier not reached: " + repr(proc.communicate()))
            if time.monotonic() > until:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.communicate()
                self.fail("barrier timed out")
            time.sleep(0.01)
        return proc, release

    def kill_crash(self, proc):
        os.killpg(proc.pid, signal.SIGKILL)
        proc.communicate(timeout=10)
        self.assertEqual(proc.returncode, -signal.SIGKILL)

    def test_each_creation_attach_start_and_file_install_crash_window_recovers(self):
        cases = [("network", ["network", "create"]), ("relay", ["create", "--name"]),
                 ("start", ["start"]), ("attach", ["network", "connect"]),
                 ("candidate-file", ["exec", "sha256sum"])]
        for label, contains in cases:
            for when in ("before", "after"):
                with self.subTest(mutation=label, window=when):
                    self.tearDown()
                    self.setUp()
                    self.arm()
                    proc, _ = self.spawn_barrier(["apply", "t1"], contains, when)
                    self.kill_crash(proc)
                    self.run_cli("rollback", "t1")
                    self.assertEqual(self.live.read_bytes(), b"original configuration\n")
                    self.assertEqual(self.state()["phase"], "rolledback")
                    self.assertEqual(set(self.fake_state()["containers"]), {CADDY})
                    self.assertEqual(self.fake_state()["networks"], {})

    def test_interrupted_rollback_retries_after_restore_stop_remove_detach(self):
        cases = [["exec", "sha256sum"], ["exec", "reload"], ["stop"], ["rm"], ["network", "disconnect"], ["network", "rm"]]
        for contains in cases:
            with self.subTest(rollback_window=contains):
                self.tearDown()
                self.setUp()
                self.arm()
                self.run_cli("apply", "t1")
                proc, _ = self.spawn_barrier(["rollback", "t1"], contains)
                self.kill_crash(proc)
                self.assertEqual(self.state()["phase"], "rollingback")
                self.run_cli("rollback", "t1")
                self.assertEqual(self.live.read_bytes(), b"original configuration\n")
                self.assertEqual(self.state()["phase"], "rolledback")
                self.assertEqual(set(self.fake_state()["containers"]), {CADDY})
                self.assertIn("dsh-mobile-relay-data", self.fake_state()["volumes"])

    def test_wrong_mount_id_extra_network_endpoint_or_labels_refuse_all_cleanup(self):
        changes = {
            "mount-source": lambda d: d["containers"][CADDY]["Mounts"][0].update(Source="/unrelated"),
            "mount-dest": lambda d: d["containers"][CADDY]["Mounts"][0].update(Destination="/other"),
            "caddy-name": lambda d: d["containers"][CADDY].update(Name="/other-caddy"),
            "caddy-id": lambda d: d["containers"][CADDY].update(Id="f" * 64),
            "relay-id": lambda d: d["containers"]["e" * 64].update(Id="f" * 64),
            "relay-label": lambda d: d["containers"]["e" * 64]["Config"]["Labels"].update({"org.dsh-mobile-relay.transaction": "other"}),
            "relay-extra-network": lambda d: d["containers"]["e" * 64]["NetworkSettings"]["Networks"].update(other={"NetworkID": "f" * 64}),
            "extra-endpoint": lambda d: d["networks"]["dsh-mobile-relay-internal"]["Containers"].update({"f" * 64: {"Name": "unrelated"}}),
            "not-internal": lambda d: d["networks"]["dsh-mobile-relay-internal"].update(Internal=False),
            "network-id": lambda d: d["networks"]["dsh-mobile-relay-internal"].update(Id="f" * 64),
        }
        self.arm()
        self.run_cli("apply", "t1")
        clean = self.fake_state()
        for label, change in changes.items():
            with self.subTest(unrelated_change=label):
                self.fake.write_text(json.dumps(clean))
                self.change_fake(change)
                before = self.fake_state()
                self.run_cli("rollback", "t1", ok=False)
                self.assertEqual(self.fake_state()["containers"], before["containers"])
                self.assertEqual(self.fake_state()["networks"], before["networks"])
                self.assertEqual(self.live.read_bytes(), self.candidate.read_bytes())

    def test_real_flock_serializes_commit_and_timer_in_both_orders(self):
        for winner in ("commit", "rollback"):
            with self.subTest(winner=winner):
                self.tearDown()
                self.setUp()
                self.arm()
                self.run_cli("apply", "t1")
                commit = ["commit", "t1", "--candidate-sha256", hashlib.sha256(self.candidate.read_bytes()).hexdigest()]
                first = commit if winner == "commit" else ["rollback", "t1"]
                contains = ["systemctl", "show"] if winner == "commit" else ["exec", "reload"]
                proc, release = self.spawn_barrier(first, contains)
                second = ["rollback", "t1"] if winner == "commit" else commit
                loser = subprocess.Popen([sys.executable, str(ROOT / "transaction.py"), *second], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                time.sleep(0.2)
                self.assertIsNone(loser.poll(), "second process bypassed real shared flock")
                release.touch()
                out, err = proc.communicate(timeout=15)
                self.assertEqual(proc.returncode, 0, out + err)
                out, err = loser.communicate(timeout=15)
                self.assertEqual(loser.returncode, 0 if winner == "commit" else 1, out + err)
                self.assertEqual(self.state()["phase"], "committed" if winner == "commit" else "rolledback")
                self.assertEqual(self.live.read_bytes(), self.candidate.read_bytes() if winner == "commit" else b"original configuration\n")

    def test_parent_only_sigkill_keeps_global_lock_until_late_mutation_child_finishes(self):
        for contains in (["network", "create"], ["create", "--name"], ["network", "connect"]):
            with self.subTest(late_mutation=contains):
                self.tearDown()
                self.setUp()
                self.arm()
                proc, release = self.spawn_barrier(["apply", "t1"], contains, "before")
                proc.kill()  # Intentionally leave its fake Docker child alive.
                proc.wait(timeout=5)
                watcher = subprocess.Popen([sys.executable, str(ROOT / "transaction.py"), "rollback", "t1"], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    time.sleep(0.2)
                    self.assertIsNone(watcher.poll(), "watchdog escaped inherited flock while mutation child was alive")
                    release.touch()
                    proc.communicate(timeout=10)
                    out, err = watcher.communicate(timeout=15)
                    self.assertEqual(watcher.returncode, 0, out + err)
                    self.assertEqual(self.state()["phase"], "rolledback")
                    self.assertEqual(self.fake_state()["networks"], {})
                    self.assertEqual(set(self.fake_state()["containers"]), {CADDY})
                finally:
                    release.touch()
                    if watcher.poll() is None:
                        watcher.kill()
                        watcher.communicate()

    def test_timer_inactive_or_changed_deadline_refuses_forward_mutation(self):
        self.arm()
        self.change_fake(lambda d: d["timers"].update({"dsh-mobile-relay-rollback-t1.timer": False}))
        self.run_cli("apply", "t1", ok=False)
        self.assertEqual(self.fake_state()["networks"], {})
        self.change_fake(lambda d: d["timers"].update({"dsh-mobile-relay-rollback-t1.timer": True}))
        unit = self.home / ".config/systemd/user/dsh-mobile-relay-rollback-t1.timer"
        unit.write_text(unit.read_text().replace("OnCalendar=", "OnCalendar=2000-01-01 00:00:00 UTC\nOld="))
        self.run_cli("apply", "t1", ok=False)
        self.assertEqual(self.fake_state()["networks"], {})

    def test_private_route_transfer_never_prints_secret_or_overwrites_existing_output(self):
        self.arm()
        self.run_cli("apply", "t1")
        output = self.home / "private/capability.json"
        result = self.run_cli("provision", "t1", "--output", str(output))
        self.assertNotIn("A" * 43, result.stdout + result.stderr)
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(output.read_text())["routeId"], "1" * 32)
        self.assertEqual(self.fake_state()["capabilities"], {})
        original = output.read_bytes()
        self.run_cli("provision", "t1", "--output", str(output), ok=False)
        self.assertEqual(output.read_bytes(), original)

    def test_route_copy_failure_retains_exact_private_orphan_recovery(self):
        self.arm()
        self.run_cli("apply", "t1")
        self.change_fake(lambda d: d.update(fail=["cp"]))
        output = self.home / "private/capability.json"
        result = self.run_cli("provision", "t1", "--output", str(output), ok=False)
        self.assertFalse(output.exists())
        self.assertNotIn("A" * 43, result.stdout + result.stderr)
        self.assertEqual(len(self.fake_state()["capabilities"]), 1)
        note = next(self.txndir.glob("route-*/recovery.json"))
        self.assertEqual(note.stat().st_mode & 0o777, 0o600)
        value = json.loads(note.read_text())
        self.assertTrue(value["container_stage"].startswith("/data/.route-capability-"))
        self.assertNotEqual(value["container_stage"], str(output))
        self.assertIn("inspect/revoke before any retry", result.stderr)

    def test_home_xdg_and_group_writable_unit_paths_are_refused(self):
        units = self.home / ".config/systemd/user"
        units.mkdir(parents=True)
        units.chmod(0o777)
        self.arm(ok=False)
        self.assertEqual(units.stat().st_mode & 0o777, 0o777)
        units.chmod(0o775)
        self.arm()
        self.run_cli("rollback", "t1")
        self.assertEqual(units.stat().st_mode & 0o777, 0o775)
        self.tearDown()
        self.setUp()
        units = self.home / ".config/systemd/user"
        units.mkdir(parents=True)
        units.chmod(0o755)
        self.env["XDG_STATE_HOME"] = str(self.root / "other-state")
        self.arm(ok=False)
        self.env.pop("XDG_STATE_HOME")
        self.arm()
        self.assertEqual(units.stat().st_mode & 0o777, 0o755)

    def test_unit_private_group_denies_other_nss_members_and_never_relaxes_secret_state(self):
        # Only the NSS predicate seam is simulated; driver filesystem/process/locks
        # remain real in all CLI tests. Existing fixture group33 is owner-only.
        import importlib.util
        from types import SimpleNamespace
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location("guard_nss", ROOT / "transaction.py")
        guard = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guard)
        with patch.object(guard.grp, "getgrgid", return_value=SimpleNamespace(gr_mem=["intruder"])):
            with self.assertRaises(guard.Refusal):
                guard.owner_only_group(os.getgid())
        with patch.object(guard.pwd, "getpwall", return_value=[SimpleNamespace(pw_uid=os.getuid() + 1, pw_gid=os.getgid())]):
            with self.assertRaises(guard.Refusal):
                guard.owner_only_group(os.getgid())
        private = self.home / ".local/state/dsh-mobile-relay"
        private.mkdir(parents=True)
        private.chmod(0o775)
        self.arm(ok=False)
        self.assertEqual(private.stat().st_mode & 0o777, 0o775)

    def test_verified_nonsecret_ancestors775_allow_complete_smoke_and_snapshot_rollback(self):
        self.mount = self.home / "example-site/caddy"
        self.mount.mkdir(parents=True)
        self.mount.parent.chmod(0o775)
        self.live = self.mount / "Caddyfile"
        self.live.write_bytes(b"original configuration\n")
        self.change_fake(lambda d: d["containers"][CADDY]["Mounts"][0].update(Source=str(self.mount)))
        local = self.home / ".local"
        local.mkdir(mode=0o775)
        local.chmod(0o775)
        units = self.home / ".config/systemd/user"
        units.mkdir(parents=True)
        for p in (units, units.parent, units.parent.parent):
            p.chmod(0o775)
        self.arm()
        self.run_cli("apply", "t1")
        result = subprocess.run([sys.executable, str(self.txndir / "driver.py"), "--home", str(self.home), "rollback", "t1", "--spec-sha256", self.state()["spec_sha256"]], env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.live.read_bytes(), b"original configuration\n")
        self.assertEqual(self.state()["phase"], "rolledback")
        for p in (local, self.mount.parent, units, units.parent, units.parent.parent):
            self.assertEqual(p.stat().st_mode & 0o777, 0o775)
        self.assertEqual(self.txndir.stat().st_mode & 0o777, 0o700)

    def test_nonsecret_exception_never_relaxes_unknown_sibling_home_file_foreign_group_or_acl(self):
        import importlib.util
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location("guard_paths", ROOT / "transaction.py")
        guard = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guard)
        driver = guard.Driver(str(self.home))
        driver.close()
        unknown = self.home / "unknown/user"
        unknown.mkdir(parents=True)
        unknown.chmod(0o775)
        with self.assertRaises(guard.Refusal):
            guard.check_chain(unknown)
        self.home.chmod(0o775)
        with self.assertRaises(guard.Refusal):
            guard.Driver(str(self.home))
        self.home.chmod(0o700)
        driver = guard.Driver(str(self.home))
        driver.close()
        local = self.home / ".local"
        local.chmod(0o775)
        with patch.object(guard.os, "getgid", return_value=os.getgid() + 1):
            with self.assertRaises(guard.Refusal):
                guard.check_chain(local)
        with patch.object(guard.os, "listxattr", return_value=["system.posix_acl_access"]):
            with self.assertRaises(guard.Refusal):
                guard.check_chain(local)
        file = local / "group-writable.txt"
        file.write_text("nonsecret")
        file.chmod(0o660)
        with self.assertRaises(guard.Refusal):
            guard.check_chain(file)
        private = local / "state/dsh-mobile-relay"
        private.chmod(0o775)
        self.arm(ok=False)
        self.assertEqual(private.stat().st_mode & 0o777, 0o775)

    def test_reload_failure_retains_resources_then_retry_rollback_succeeds(self):
        self.arm()
        self.change_fake(lambda d: d.update(fail=["exec", "reload"]))
        self.run_cli("apply", "t1", ok=False)
        self.run_cli("rollback", "t1", ok=False)
        self.assertEqual(self.state()["phase"], "rollingback")
        self.assertIn("e" * 64, self.fake_state()["containers"])
        self.change_fake(lambda d: d.update(fail=None))
        self.run_cli("rollback", "t1")
        self.assertEqual(self.state()["phase"], "rolledback")


if __name__ == "__main__":
    unittest.main(verbosity=2)
