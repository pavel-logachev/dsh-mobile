#!/usr/bin/env python3
"""Isolated real Engine create/start/health/hardening check. No public ports.

Temporary uniquely named network/container/volume are created only after absence
and removed by exact identities; no production names, secrets or remote daemon.
"""
import ast
import json
from pathlib import Path
import subprocess
import time
import uuid

here = Path(__file__).resolve().parent
# Read only constants and the pure hardening validator from the Linux driver;
# do not import fcntl/pwd on Windows and do not emulate any locking here.
tree = ast.parse((here / "transaction.py").read_text())
namespace = {}
exec("class Refusal(Exception): pass\ndef need(c,m):\n if not c: raise Refusal(m)", namespace)
for item in tree.body:
    if isinstance(item, ast.Assign) and any(isinstance(t, ast.Name) and t.id in {"HEALTH", "CMD", "VOLUME"} for t in item.targets):
        exec(compile(ast.Module(body=[item], type_ignores=[]), "<policy>", "exec"), namespace)
    if isinstance(item, ast.ClassDef) and item.name == "Driver":
        method = next(m for m in item.body if isinstance(m, ast.FunctionDef) and m.name == "hardening")
        exec(compile(ast.Module(body=[method], type_ignores=[]), "<policy>", "exec"), namespace)


def docker(*args):
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=25, check=True).stdout.strip()


def inspect(*args):
    return json.loads(docker(*args))[0]


if docker("context", "show") != "desktop-linux":
    raise SystemExit("refusing nonlocal context")
image = inspect("image", "inspect", "dsh-mobile-relay:0.2.0")["Id"]
suffix = uuid.uuid4().hex[:12]
name = "dsh-relay-guard-smoke-" + suffix
network_name = name + "-internal"
volume = name + "-data"
for command, column, target in ((["container", "ls", "--all"], "{{.Names}}", name), (["network", "ls"], "{{.Name}}", network_name), (["volume", "ls"], "{{.Name}}", volume)):
    if target in docker(*command, "--format", column).splitlines():
        raise SystemExit("temporary name collision")
network_id = container_id = None
try:
    network_id = docker("network", "create", "--internal", "--label", "org.dsh-mobile-relay.smoke=" + suffix, network_name)
    args = ["create", "--name", name, "--label", "org.dsh-mobile-relay.smoke=" + suffix, "--network", network_id,
            "--init", "--user", "10001:10001", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--memory", "512m", "--cpus", "0.5", "--pids-limit", "128", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=16m,mode=1777",
            "--mount", "type=volume,source=" + volume + ",target=/data", "--health-cmd", 'node -e "' + namespace["HEALTH"] + '"',
            "--health-interval", "10s", "--health-timeout", "3s", "--health-retries", "3", "--health-start-period", "10s",
            "--restart", "unless-stopped", "--log-driver", "json-file", "--log-opt", "max-size=10m", "--log-opt", "max-file=3", image, *namespace["CMD"]]
    container_id = docker(*args)
    created = inspect("inspect", container_id)
    created_map = created["NetworkSettings"]["Networks"]
    docker("start", container_id)
    deadline = time.monotonic() + 25
    while True:
        running = inspect("inspect", container_id)
        if running["State"].get("Health", {}).get("Status") == "healthy":
            break
        if time.monotonic() >= deadline:
            raise SystemExit("isolated health check exceeded deadline")
        time.sleep(0.2)
    health = json.loads(docker("exec", container_id, "node", "-e", "fetch('http://127.0.0.1:8088/healthz').then(async r=>{console.log(await r.text());if(!r.ok)process.exit(1)})"))
    namespace["VOLUME"] = volume  # Only name differs from production policy.
    namespace["hardening"](type("Policy", (), {"spec": {"image_id": image}})(), running)
    net = inspect("network", "inspect", network_id)
    assert net["Internal"] is True and set(net["Containers"]) == {container_id}
    assert set(running["NetworkSettings"]["Networks"]) == {network_name}
    assert running["NetworkSettings"]["Networks"][network_name]["NetworkID"] == network_id
    print(json.dumps({"status": "PASS", "imageId": image, "createdNetworkId": created_map[network_name]["NetworkID"], "runningNetworkId": network_id,
                      "health": health, "hardening": "all runtime assertions PASS", "publishedPorts": running["HostConfig"]["PortBindings"]}, indent=2))
finally:
    if container_id:
        exact = inspect("inspect", container_id)
        assert exact["Id"] == container_id and exact["Config"]["Labels"]["org.dsh-mobile-relay.smoke"] == suffix
        if exact["State"]["Running"]:
            docker("stop", "--time", "10", container_id)
        docker("rm", container_id)
    if network_id:
        exact = inspect("network", "inspect", network_id)
        assert exact["Id"] == network_id and exact["Labels"]["org.dsh-mobile-relay.smoke"] == suffix and not exact["Containers"]
        docker("network", "rm", network_id)
    if volume in docker("volume", "ls", "--format", "{{.Name}}").splitlines():
        exact = inspect("volume", "inspect", volume)
        assert exact["Name"] == volume
        docker("volume", "rm", volume)  # This disposable smoke volume only, never the durable relay volume.
