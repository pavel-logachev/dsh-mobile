#!/bin/sh
# Manual resource creation/recording cannot prove crash-safe ownership. Disabled.
set -eu
printf '%s\n' 'record-resources is disabled: use transaction.py apply TXN; it journals before every mutation.' >&2
exit 2
