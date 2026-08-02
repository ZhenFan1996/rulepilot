#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUTPUT_DIR=$(mktemp -d)
OUTPUT_FILE="$OUTPUT_DIR/mockito-agent.log"
trap 'rm -rf "$OUTPUT_DIR"' EXIT

if ! (cd "$ROOT_DIR/backend" && ./mvnw -q -Dtest=JpaRuleDocumentRepositoryTest test) >"$OUTPUT_FILE" 2>&1; then
	cat "$OUTPUT_FILE"
	exit 1
fi

if grep -E "Mockito is currently self-attaching|A Java agent has been loaded dynamically|Dynamic loading of agents" \
	"$OUTPUT_FILE" >/dev/null 2>&1; then
	cat "$OUTPUT_FILE"
	echo "Mockito explicit-agent verification failed."
	exit 1
fi

echo "Mockito explicit-agent verification passed."
