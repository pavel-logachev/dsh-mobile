#!/bin/sh
# New CLI only: arm TXN --live ... --candidate ... (see --help and deployment doc).
set -eu
exec python3 "$(dirname "$0")/transaction.py" arm "$@"
