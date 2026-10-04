#!/usr/bin/env python3
"""Run POSIX tests in an existing local Python image; no mounts/socket/network."""
import argparse
import os
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image', default=os.environ.get('RELAY_TEST_IMAGE'), help='Existing local image with sh, tar and Python 3; never pulled')
args, tests = parser.parse_known_args()
if not args.image:
    parser.error('supply --image or RELAY_TEST_IMAGE with a vetted existing local Python image')
root = Path(__file__).resolve().parent
context = subprocess.run(["docker", "context", "show"], capture_output=True, text=True, timeout=15, check=True).stdout.strip()
if context != "desktop-linux":
    raise SystemExit("refusing nonlocal Docker context")
name = "dsh-relay-guard-test-" + uuid.uuid4().hex[:12]
buf = io.BytesIO()
with tarfile.open(fileobj=buf, mode="w") as archive:
    for path in sorted(root.iterdir()):
        if path.suffix in (".py", ".sh"):
            archive.add(path, arcname=path.name)
try:
    result = subprocess.run([
        "docker", "run", "--rm", "--name", name, "-i", "--network", "none", "--read-only",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "33:33",
        "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=128m", "--pull", "never", "--entrypoint", "sh", args.image,
        "-c", "mkdir /tmp/suite && tar -xf - -C /tmp/suite && exec python3 /tmp/suite/test_transaction.py \"$@\"", "relay-tests", *tests,
    ], input=buf.getvalue(), timeout=240)
    raise SystemExit(result.returncode)
except subprocess.TimeoutExpired:
    # This unique exact name was created only by this runner; never target real services.
    subprocess.run(["docker", "stop", "--time", "2", name], timeout=15, check=False)
    raise SystemExit("local test runner exceeded 240-second budget")
