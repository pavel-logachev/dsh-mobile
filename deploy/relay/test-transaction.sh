#!/bin/sh
# POSIX Linux tests with fake Docker/systemd and REAL filesystem/flock/process races.
set -eu
exec python3 "$(dirname "$0")/test_transaction.py" "$@"
