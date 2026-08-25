#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$ROOT_DIR/scripts/require-paid-canary-authorization.sh"

cd "$ROOT_DIR"
exec node scripts/generate-public-corpus-entry.mjs "$@"
