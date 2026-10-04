#!/bin/sh
# Exact read-only topology check only. Attach happens exclusively inside guarded apply.
set -eu
[ "$#" = 1 ] || { printf '%s\n' 'usage: reconcile-caddy-network.sh TXN (read-only); use guarded apply for attachment' >&2; exit 2; }
exec python3 "$(dirname "$0")/transaction.py" check "$1"
