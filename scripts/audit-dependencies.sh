#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RESULT_DIR="$ROOT_DIR/.local/security"
MAVEN_LIST="$RESULT_DIR/maven-runtime-dependencies.txt"
OSV_QUERY="$RESULT_DIR/osv-query.json"
OSV_RESULT="$RESULT_DIR/osv-result.json"

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		echo "FAIL $1 is required" >&2
		exit 1
	}
}

require_command curl
require_command jq
require_command npm
mkdir -p "$RESULT_DIR"

(cd "$ROOT_DIR/frontend" && npm audit --audit-level=low)

(cd "$ROOT_DIR/backend" && ./mvnw -q dependency:list \
	-DincludeScope=runtime \
	-DoutputAbsoluteArtifactFilename=false \
	-DoutputFile="$MAVEN_LIST")

awk '/^   / {
	split($1, coordinate, ":")
	if (length(coordinate) >= 5) {
		print coordinate[1] "\t" coordinate[2] "\t" coordinate[4]
	}
}' "$MAVEN_LIST" | sort -u | jq -Rn '
	[inputs | split("\t") | {
		package: {ecosystem: "Maven", name: (.[0] + ":" + .[1])},
		version: .[2]
	}] | {queries: .}' >"$OSV_QUERY"

dependency_count=$(jq '.queries | length' "$OSV_QUERY")
[ "$dependency_count" -gt 0 ] || {
	echo "FAIL no Maven runtime dependencies were resolved" >&2
	exit 1
}

curl -fsS --retry 2 --connect-timeout 10 --max-time 60 \
	-H 'Content-Type: application/json' \
	--data-binary "@$OSV_QUERY" \
	https://api.osv.dev/v1/querybatch >"$OSV_RESULT"

vulnerability_count=$(jq '[.results[]?.vulns[]?.id] | unique | length' "$OSV_RESULT")
if [ "$vulnerability_count" -gt 0 ]; then
	echo "FAIL OSV reported $vulnerability_count known vulnerability record(s):" >&2
	jq -r '[.results[]?.vulns[]?] | unique_by(.id)[] | "  \(.id): \(.summary // "no summary")"' "$OSV_RESULT" >&2
	exit 1
fi

echo "PASS npm audit: no known dependency vulnerabilities"
echo "PASS OSV audit: $dependency_count Maven runtime dependencies, no known vulnerabilities"
