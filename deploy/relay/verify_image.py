#!/usr/bin/env python3
"""Verify current compiled relay artifacts against the existing local image."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

IMAGE = "dsh-mobile-relay:0.2.0"
root = Path(__file__).resolve().parents[2]
context = subprocess.run(["docker", "context", "show"], capture_output=True, text=True, check=True, timeout=15).stdout.strip()
if context != "desktop-linux":
    raise SystemExit("refusing nonlocal context")
code = """const fs=require('node:fs'),crypto=require('node:crypto');
const artifacts={};for(const n of ['cli','index','server','state'])artifacts['dist/'+n+'.js']=crypto.createHash('sha256').update(fs.readFileSync('dist/'+n+'.js')).digest('hex');
console.log(JSON.stringify({node:process.version,ws:require('ws/package.json').version,artifacts}));"""
result = subprocess.run(["docker", "run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "10001:10001", "--entrypoint", "node", IMAGE, "-e", code], capture_output=True, text=True, check=True, timeout=20)
actual = json.loads(result.stdout)
image_id = json.loads(subprocess.run(["docker", "image", "inspect", IMAGE], capture_output=True, text=True, check=True, timeout=15).stdout)[0]["Id"]
expected = {"dist/" + p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (root / "relay/dist").glob("*.js")}
if actual["artifacts"] != expected or actual["node"] != "v24.21.0" or actual["ws"] != "8.22.0":
    print(json.dumps({"status": "MISMATCH", "imageId": image_id, "image": actual, "currentCompiled": expected}, indent=2))
    raise SystemExit(1)
print(json.dumps({"status": "PASS", "dockerContext": context, "imageId": image_id, **actual}, indent=2))
