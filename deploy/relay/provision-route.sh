#!/bin/sh
# New CLI: TXN --output /owner/private/new-capability.json; never prints a token.
set -eu
exec python3 "$(dirname "$0")/transaction.py" provision "$@"
