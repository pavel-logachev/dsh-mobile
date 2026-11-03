"""Test-only Docker/systemd simulator; never invokes Docker or a network."""
import datetime
import fcntl
import json
import os
from pathlib import Path
import sys
import time

p = Path(os.environ["FAKE_STATE"])
command = Path(sys.argv[0]).name
args = sys.argv[1:]
docker_endpoint = None
if command == "docker" and args[:1] == ["--host"]:
    docker_endpoint, args = args[1], args[2:]
with open(str(p) + ".lock", "a") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    data = json.loads(p.read_text())
    call = ([command] if command != "docker" else []) + args
    data["calls"].append(call)
    data.setdefault("child_environments", []).append({"command": command, "env_keys": sorted(k for k in os.environ if k != "FAKE_STATE"), "docker_endpoint": docker_endpoint, "path": os.environ.get("PATH"), "home": os.environ.get("HOME"), "tz": os.environ.get("TZ")})
    fail = data.get("fail")
    if fail and all(part in call for part in fail):
        p.write_text(json.dumps(data))
        sys.exit(1)
    barrier = json.loads(Path(data["barrier"]).read_text()) if data.get("barrier") else None
    match = barrier and all(part in call for part in barrier["contains"])
    if match:
        data.pop("barrier", None)  # One-shot seam; later rollback must not re-block.
    if match and barrier["when"] == "before":
        p.write_text(json.dumps(data))
        Path(barrier["ready"]).touch()
        while not Path(barrier["release"]).exists():
            time.sleep(0.01)
    out = ""
    code = 0
    if command == "curl":
        out = str(data.get("http_status", 200))
    elif command == "loginctl":
        out = "yes"
    elif command == "systemctl":
        verb = args[1]
        name = args[-1]
        if verb == "enable":
            data["timers"][name] = True
        elif verb == "disable":
            data["timers"][name] = False
        elif verb == "is-active":
            code = 0 if data["timers"].get(name, False) else 3
        elif verb == "show":
            unit = args[2]
            home = Path(os.environ["HOME"])
            text = (home / ".config/systemd/user" / unit).read_text()
            date = next(line.split("=", 1)[1] for line in text.splitlines() if line.startswith("OnCalendar="))
            out = datetime.datetime.strptime(date, "%Y-%m-%d %H:%M:%S UTC").strftime("%a %Y-%m-%d %H:%M:%S UTC")
    elif args[:2] == ["container", "ls"]:
        out = "\n".join(json.dumps({"ID": d["Id"], "Names": d["Name"].lstrip("/")}) for d in data["containers"].values())
    elif args[:2] == ["network", "ls"]:
        out = "\n".join(json.dumps({"ID": d["Id"], "Name": d["Name"]}) for d in data["networks"].values())
    elif args[:2] == ["volume", "ls"]:
        out = "\n".join(json.dumps({"Name": name}) for name in data["volumes"])
    elif args[:2] == ["image", "inspect"]:
        out = json.dumps([{"Id": args[2], "Config": {"User": "10001:10001", "Entrypoint": ["node", "dist/cli.js"]}}])
    elif args[:2] == ["network", "create"]:
        labels = dict(args[i + 1].split("=", 1) for i, value in enumerate(args) if value == "--label")
        name = args[-1]
        data["networks"][name] = {"Id": "c" * 64, "Name": name, "Internal": True, "Driver": "bridge", "Labels": labels, "Containers": {}}
        out = "c" * 64
    elif args[:2] == ["network", "inspect"]:
        item = next((d for d in data["networks"].values() if args[-1] in (d["Id"], d["Name"])), None)
        out = json.dumps([item]) if item else ""
        code = 0 if item else 1
    elif args[0] == "create":
        labels = dict(args[i + 1].split("=", 1) for i, value in enumerate(args) if value == "--label")
        network = next(d for d in data["networks"].values() if args[args.index("--network") + 1] in (d["Id"], d["Name"]))
        image = next(value for value in args if value.startswith("sha256:"))
        cid = "9" * 64 if "e" * 64 in data["containers"] else "e" * 64
        container_name = args[args.index("--name") + 1]
        aliases = [args[i + 1] for i, value in enumerate(args) if value == "--network-alias"]
        health = args[args.index("--health-cmd") + 1]
        data["containers"][cid] = {
            "Id": cid, "Name": "/" + container_name, "Image": image,
            "Config": {"User": "10001:10001", "Entrypoint": ["node", "dist/cli.js"], "Cmd": args[args.index(image) + 1:], "Labels": labels,
                       "Healthcheck": {"Test": ["CMD-SHELL", health], "Interval": 10000000000, "Timeout": 3000000000, "Retries": 3, "StartPeriod": 10000000000}},
            "HostConfig": {"ReadonlyRootfs": True, "Init": True, "Privileged": False, "CapDrop": ["ALL"], "CapAdd": None, "Devices": [], "SecurityOpt": ["no-new-privileges:true"],
                           "Memory": 536870912, "NanoCpus": 500000000, "PidsLimit": 128, "RestartPolicy": {"Name": "unless-stopped"}, "PortBindings": {}, "PublishAllPorts": False,
                           "LogConfig": {"Type": "json-file", "Config": {"max-size": "10m", "max-file": "3"}}, "Tmpfs": {"/tmp": "rw,noexec,nosuid,nodev,size=16m,mode=1777"}},
            "Mounts": [{"Type": "volume", "Name": "dsh-mobile-relay-data", "Destination": "/data", "RW": True}],
            "NetworkSettings": {"Networks": {network["Name"]: {"NetworkID": "", "Aliases": aliases}}, "Ports": {"8088/tcp": None}},
            "State": {"Running": False, "Status": "created", "Health": {"Status": "starting"}},
        }
        if "--env-file" in args:
            env_file = Path(args[args.index("--env-file") + 1])
            data.setdefault("env_file_modes", []).append(env_file.stat().st_mode & 0o777)
            values = env_file.read_text().split("\n")
            if values and values[-1] == "":
                values.pop()
            if values:
                data["containers"][cid]["Config"]["Env"] = values
        data["volumes"]["dsh-mobile-relay-data"] = {}
        out = cid
    elif args[0] == "start":
        item = data["containers"][args[1]]
        item["State"] = {"Running": True, "Status": "running", "Health": {"Status": data.get("start_health", "healthy") if args[1] == "9" * 64 else "healthy"}}
        n = data["networks"]["dsh-mobile-relay-internal"]
        item["NetworkSettings"]["Networks"][n["Name"]]["NetworkID"] = n["Id"]
        n["Containers"][args[1]] = {"Name": "dsh-mobile-relay"}
    elif args[0] == "rename":
        if any(c["Name"] == "/" + args[2] for c in data["containers"].values()):
            code = 1
        else:
            data["containers"][args[1]]["Name"] = "/" + args[2]
    elif args[0] == "stop":
        item = data["containers"][args[-1]]
        item["State"]["Running"] = False
        item["State"]["Status"] = "exited"
        for n in data["networks"].values():
            n["Containers"].pop(args[-1], None)
    elif args[0] == "rm":
        cid = args[-1]
        del data["containers"][cid]
        for n in data["networks"].values():
            n["Containers"].pop(cid, None)
    elif args[:2] == ["network", "connect"]:
        n = next(d for d in data["networks"].values() if d["Id"] == args[2])
        item = data["containers"][args[3]]
        item["NetworkSettings"]["Networks"][n["Name"]] = {"NetworkID": n["Id"]}
        n["Containers"][args[3]] = {"Name": item["Name"].lstrip("/")}
    elif args[:2] == ["network", "disconnect"]:
        n = next(d for d in data["networks"].values() if d["Id"] == args[2])
        data["containers"][args[3]]["NetworkSettings"]["Networks"].pop(n["Name"], None)
        n["Containers"].pop(args[3], None)
    elif args[:2] == ["network", "rm"]:
        name = next(name for name, d in data["networks"].items() if d["Id"] == args[2])
        del data["networks"][name]
    elif args[0] == "exec" and "provision" in args:
        stage = args[-1]
        data.setdefault("capabilities", {})[stage] = {"routeId": "1" * 32, "connectorToken": "A" * 43}
        data.setdefault("routes", []).append("1" * 32)
    elif args[0] == "cp":
        stage = args[1].split(":", 1)[1]
        target = Path(args[2])
        with target.open("x") as stream:
            json.dump(data["capabilities"][stage], stream)
    elif args[0] == "exec" and "node" in args and "-e" in args:
        if "/healthz" in args[-1]:
            data.setdefault("direct_health_ids", []).append(args[1])
            out = str(data.get("direct_status", 200))
            code = 0 if out == "200" else 1
        else:
            data["capabilities"].pop(args[-1], None)
    elif args[0] == "exec" and "validate" in args:
        sys.stdin.buffer.read()
    elif args[0] == "exec" and "sha256sum" in args:
        import hashlib
        item = data["containers"][args[1]]
        mount = item["Mounts"][0]
        out = hashlib.sha256((Path(mount["Source"]) / "Caddyfile").read_bytes()).hexdigest() + "  " + args[-1]
    elif args[0] == "exec" and "reload" in args:
        pass
    elif args[0] == "inspect":
        item = next((d for d in data["containers"].values() if args[-1] in (d["Id"], d["Name"].lstrip("/"))), None)
        out = json.dumps([item]) if item else ""
        code = 0 if item else 1
    else:
        raise RuntimeError("unsupported fake command " + command + " " + repr(args))
    p.write_text(json.dumps(data))
    if match and barrier["when"] == "after":
        Path(barrier["ready"]).touch()
        while not Path(barrier["release"]).exists():
            time.sleep(0.01)
    print(out)
    sys.exit(code)
