#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

failures=0

require_file() {
	if [ ! -f "$1" ]; then
		echo "FAIL missing file: $1"
		failures=$((failures + 1))
	else
		echo "PASS file: $1"
	fi
}

require_directory() {
	if [ ! -d "$1" ]; then
		echo "FAIL missing directory: $1"
		failures=$((failures + 1))
	else
		echo "PASS directory: $1"
	fi
}

# Keep this gate at the stable repository boundary. Individual scripts, tests,
# fixtures, routes, and workflows are exercised by their owning commands; listing
# all of them here only turns harmless moves and deletions into bootstrap failures.
require_file "README.md"
require_file "AGENTS.md"
require_file "Makefile"
require_file ".env.example"
require_file ".gitignore"
require_file "backend/pom.xml"
require_file "backend/mvnw"
require_file "frontend/package.json"
require_file "frontend/package-lock.json"
require_file "infra/compose.yml"

require_directory "backend"
require_directory "frontend"
require_directory "infra"
require_directory "scripts"
require_directory "examples/evaluation"
require_directory ".github/workflows"

if [ "$failures" -ne 0 ]; then
	echo "Foundation verification failed with $failures issue(s)."
	exit 1
fi

echo "Foundation verification passed."
