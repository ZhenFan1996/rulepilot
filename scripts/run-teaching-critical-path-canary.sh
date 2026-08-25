#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# The former critical-path class was retired. Keep this public command useful by forwarding it to
# the maintained one-rulebook teaching publication canary, which owns the current paid contract.
exec sh "$ROOT_DIR/scripts/run-teaching-richness-canary.sh"
