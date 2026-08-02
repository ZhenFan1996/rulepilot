#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUTPUT_DIR=$(mktemp -d)
OUTPUT_FILE="$OUTPUT_DIR/archunit-imports.log"
trap 'rm -rf "$OUTPUT_DIR"' EXIT

if ! (cd "$ROOT_DIR/backend" && ./mvnw -q -Dtest=ModuleArchitectureTest test) >"$OUTPUT_FILE" 2>&1; then
	cat "$OUTPUT_FILE"
	exit 1
fi

if grep -F "Could not find matching origin for synthetic method" "$OUTPUT_FILE" >/dev/null 2>&1; then
	cat "$OUTPUT_FILE"
	echo "ArchUnit import verification failed."
	exit 1
fi

echo "ArchUnit import verification passed."
