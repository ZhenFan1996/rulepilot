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
require_file "scripts/verify-documentation.mjs"
require_file "scripts/verify-documentation.test.mjs"
require_file "scripts/verify-compose.sh"
require_file "scripts/verify-architecture.sh"
require_file "scripts/run-integration-tests.sh"
require_file "scripts/evaluate-product.mjs"
require_file "scripts/evaluate-product.test.mjs"
require_file "scripts/evaluate-agent-baseline.mjs"
require_file "scripts/evaluate-agent-baseline.test.mjs"
require_file "scripts/evaluate-agent-security.mjs"
require_file "scripts/evaluate-agent-security.test.mjs"
require_file "scripts/verify-complete-agent-release.mjs"
require_file "scripts/verify-complete-agent-release.test.mjs"
require_file "scripts/verify-conversational-agent-release.mjs"
require_file "scripts/verify-conversational-agent-release.test.mjs"
require_file "scripts/probe-model-tool-capabilities.mjs"
require_file "scripts/probe-model-tool-capabilities.test.mjs"
require_file "scripts/run-real-agent-tool-loop.sh"
require_file "scripts/run-real-answer-agent.sh"
require_file "scripts/run-real-teaching-agent.sh"
require_file "scripts/run-real-visual-agent.sh"
require_file "scripts/run-real-context-agent.sh"
require_file "scripts/run-real-recommendation-agent.sh"
require_file "scripts/run-real-rulebook-acquisition.sh"
require_file "scripts/preflight-public-rulebook.mjs"
require_file "scripts/preflight-public-rulebook.test.mjs"
require_file "scripts/smoke-production-ordinary-user.sh"
require_file "scripts/smoke-production-ordinary-user.test.mjs"
require_file "scripts/manage-public-lesson-candidate.sh"
require_file "scripts/manage-public-lesson-candidate.test.mjs"
require_file "scripts/verify-ci-workflow.test.mjs"
require_file "scripts/verify-mockito-agent.sh"
require_file "scripts/verify-archunit-imports.sh"
require_file "scripts/verify-frontend-delivery.mjs"
require_file "examples/evaluation/lantern-relay/product-evaluation.json"
require_file "examples/evaluation/lantern-relay/lesson.json"
require_file "examples/evaluation/lantern-relay/execution.json"
require_file "examples/evaluation/agent-tool-protocol-v1.json"
require_file "examples/evaluation/complete-agent-release-v1.json"
require_file "examples/evaluation/conversational-agent-release-v1.json"
require_file "infra/compose.yml"
require_file "infra/postgres/init/001-enable-vector.sql"
require_file "backend/pom.xml"
require_file "backend/mvnw"
require_file "backend/mvnw.cmd"
require_file "backend/.mvn/wrapper/maven-wrapper.properties"
require_file "backend/src/main/java/com/rulepilot/RulePilotApplication.java"
require_file "backend/src/main/resources/application.yml"
require_file "frontend/package.json"
require_file "frontend/package-lock.json"
require_file "frontend/vite.config.ts"
require_file "frontend/playwright.config.ts"
require_file "frontend/e2e/home.spec.ts"
require_file "frontend/src/main.ts"
require_file ".github/workflows/ci.yml"
require_file ".github/workflows/production-ordinary-user-smoke.yml"
require_file ".github/workflows/public-lesson-candidate.yml"

require_directory "backend"
require_directory "frontend"
require_directory "infra"
require_directory "scripts"
require_directory "examples/evaluation"
require_directory ".github/workflows"

for model_config in .env.example backend/src/main/resources/application.yml infra/compose.deployment.yml .github/workflows/deploy-production.yml; do
	if grep -Eiq '(^|[:=][[:space:]]*\$\{?[^}]*:|=)qwen-plus([_-]|$)' "$model_config"; then
		echo "FAIL prohibited qwen-plus runtime configuration: $model_config"
		failures=$((failures + 1))
	else
		echo "PASS no prohibited qwen-plus runtime configuration: $model_config"
	fi
done

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
