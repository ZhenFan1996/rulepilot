#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# Keep the historical entrypoint usable, but give the paid run one current owner. The former test
# class was retired; the richness canary now exercises the maintained full teaching publication path.
exec sh "$ROOT_DIR/scripts/run-teaching-richness-canary.sh"
