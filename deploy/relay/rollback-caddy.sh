#!/bin/sh
# The user timer uses the immutable transaction-local Python snapshot, not this wrapper.
set -eu
exec python3 "$(dirname "$0")/transaction.py" rollback "$@"
