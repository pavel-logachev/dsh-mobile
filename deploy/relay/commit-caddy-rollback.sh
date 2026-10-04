#!/bin/sh
# New CLI: TXN --candidate-sha256 HASH. Never automatically commits after health alone.
set -eu
exec python3 "$(dirname "$0")/transaction.py" commit "$@"
