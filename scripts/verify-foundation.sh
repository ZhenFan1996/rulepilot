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

require_file "README.md"
require_file "Makefile"
require_file ".env.example"
require_file ".gitignore"
require_file "scripts/verify-foundation.sh"
require_file "backend/pom.xml"
require_file "backend/mvnw"
require_file "backend/mvnw.cmd"
require_file "backend/.mvn/wrapper/maven-wrapper.properties"
require_file "backend/src/main/java/com/rulepilot/RulePilotApplication.java"
require_file "backend/src/main/resources/application.yml"
require_file "frontend/package.json"
require_file "frontend/package-lock.json"
require_file "frontend/vite.config.ts"
require_file "frontend/src/main.ts"

require_directory "backend"
require_directory "frontend"
require_directory "infra"
require_directory "scripts"
require_directory ".github/workflows"

readme_links=$(sed -n 's/.*](\([^)#?]*\).*/\1/p' README.md)
for link in $readme_links; do
	case "$link" in
		http://*|https://*|mailto:*)
			;;
		/*)
			echo "FAIL README link is not repository-relative: $link"
			failures=$((failures + 1))
			;;
		*)
			if [ ! -e "$link" ]; then
				echo "FAIL broken README link: $link"
				failures=$((failures + 1))
			else
				echo "PASS README link: $link"
			fi
			;;
	esac
done

if [ "$failures" -ne 0 ]; then
	echo "Foundation verification failed with $failures issue(s)."
	exit 1
fi

echo "Foundation verification passed."
