#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DOMAIN_ROOT="$ROOT_DIR/backend/src/main/java"

forbidden_imports='^import (org\.springframework|jakarta\.persistence|org\.hibernate|org\.springframework\.data|org\.springframework\.amqp|org\.springframework\.session|redis\.clients|com\.rabbitmq|software\.amazon\.awssdk)'
violations=0
domain_files=0

while IFS= read -r source_file; do
	[ -n "$source_file" ] || continue
	domain_files=$((domain_files + 1))

	if grep -En "$forbidden_imports" "$source_file"; then
		echo "FAIL domain source depends on framework or infrastructure: $source_file"
		violations=$((violations + 1))
	fi
done <<EOF
$(find "$DOMAIN_ROOT" -type f -path '*/domain/*.java' -not -name '._*' | sort)
EOF

if [ "$violations" -ne 0 ]; then
	echo "Architecture verification failed with $violations violation(s)."
	exit 1
fi

if [ "$domain_files" -eq 0 ]; then
	echo "Architecture verification passed; no business domain packages exist in the Phase 0 baseline."
else
	echo "Architecture verification passed for $domain_files domain source file(s)."
fi
