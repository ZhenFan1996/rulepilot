SHELL := /bin/sh

PRODUCT_EVAL_DATASET ?= examples/evaluation/lantern-relay/product-evaluation.json
PRODUCT_EVAL_OUTPUT ?= .local/product-evaluation/latest-report.json
CORPUS_TEACHING ?= deepseek
CORPUS_VISUAL ?= qwen
CORPUS_ANSWER ?= deepseek
CORPUS_CRITIC ?= deepseek
CORPUS_TIMEOUT_MINUTES ?= 20
CORPUS_RESTART ?=
CORPUS_REFRESH_PLAN ?=
AGENT_BASELINE_MANIFEST ?= .local/agent-evaluation/manifest.json
AGENT_BASELINE_OUTPUT ?= .local/agent-evaluation/application-harness-baseline.json
AGENT_TOOL_PROBE_OUTPUT ?= .local/agent-evaluation/provider-capabilities.json

.PHONY: help bootstrap dev dev-split dev-stop demo-data product-eval corpus-preflight corpus-cover-discover corpus-generate agent-baseline agent-tool-probe agent-tool-loop-real agent-answer-real agent-teaching-real agent-visual-real agent-context-real agent-security-real agent-release-real format backend-test frontend-test integration-test performance-test security-test e2e verify compose-up compose-down deployment-up deployment-down production-up production-down

help: ## Show the available repository commands
	@awk 'BEGIN {FS = ":.*##"; printf "RulePilot commands:\n\n"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  %-20s %s\n", $$1, $$2} END {printf "\n"}' $(MAKEFILE_LIST)

bootstrap: ## Validate the current repository foundation
	@sh scripts/verify-foundation.sh

dev: ## Start backend and frontend development servers
	@bash scripts/run-dev.sh combined

dev-split: ## Start separate API and worker processes plus the frontend
	@bash scripts/run-dev.sh split

dev-stop: ## Stop RulePilot processes left on local development ports
	@bash scripts/stop-dev.sh

demo-data: ## Load the self-authored demo rulebook into a running local backend
	@sh scripts/load-demo-data.sh

product-eval: ## Evaluate a lesson against an external ordinary-player product dataset
	@node scripts/evaluate-product.mjs --dataset "$(PRODUCT_EVAL_DATASET)" --output "$(PRODUCT_EVAL_OUTPUT)"

corpus-preflight: ## Validate one local publisher rulebook before public-corpus generation (PDF= SOURCE= COVER= or BGG_ID=)
	@test -n "$(PDF)" || (echo "PDF is required"; exit 2)
	@test -n "$(SOURCE)" || (echo "SOURCE is required"; exit 2)
	@test -n "$(COVER)$(BGG_ID)" || (echo "COVER or BGG_ID is required"; exit 2)
	@node scripts/preflight-public-rulebook.mjs --pdf "$(PDF)" --source "$(SOURCE)" $(if $(COVER),--cover "$(COVER)") $(if $(BGG_ID),--bgg-id "$(BGG_ID)")

corpus-cover-discover: ## Find a title-matching cover on an official publisher page (SOURCE= TITLE=)
	@test -n "$(SOURCE)" || (echo "SOURCE is required"; exit 2)
	@test -n "$(TITLE)" || (echo "TITLE is required"; exit 2)
	@node scripts/discover-publisher-cover.mjs --source "$(SOURCE)" --title "$(TITLE)"

corpus-generate: ## Generate one resumable public lesson from the ignored corpus manifest (TITLE=)
	@test -n "$(TITLE)" || (echo "TITLE is required"; exit 2)
	@node scripts/generate-public-corpus-entry.mjs --title "$(TITLE)" \
		--timeout-minutes "$(CORPUS_TIMEOUT_MINUTES)" $(if $(CORPUS_RESTART),--restart) $(if $(CORPUS_REFRESH_PLAN),--refresh-plan) \
		--teaching "$(CORPUS_TEACHING)" --visual "$(CORPUS_VISUAL)" \
		--answer "$(CORPUS_ANSWER)" --critic "$(CORPUS_CRITIC)"

agent-baseline: ## Validate and summarize the ignored five-family real-rulebook Agent baseline
	@node scripts/evaluate-agent-baseline.mjs --manifest "$(AGENT_BASELINE_MANIFEST)" --output "$(AGENT_BASELINE_OUTPUT)"

agent-tool-probe: ## Probe enabled paid models with bounded synthetic required-tool/no-tool requests
	@node scripts/probe-model-tool-capabilities.mjs --output "$(AGENT_TOOL_PROBE_OUTPUT)"

agent-tool-loop-real: ## Run the bounded Spring AI tool loop against two ignored real rulebooks and paid models
	@sh scripts/run-real-agent-tool-loop.sh

agent-answer-real: ## Evaluate observation-driven answer evidence refinement on ignored real rulebooks
	@sh scripts/run-real-answer-agent.sh

agent-teaching-real: ## Evaluate coverage-led teaching evidence refinement on ignored real rulebooks
	@sh scripts/run-real-teaching-agent.sh

agent-visual-real: ## Evaluate capability-scoped multimodal tools on ignored real rulebooks
	@sh scripts/run-real-visual-agent.sh

agent-context-real: ## Evaluate multi-turn context, recovery, and fallback on ignored real rulebooks
	@sh scripts/run-real-context-agent.sh

agent-security-real: ## Validate adversarial tools and all five ignored real-rulebook families
	@cd backend && ./mvnw -q -Dtest=NativeAgentSecurityEvaluationTest,NativeReadToolsTest,BoundedNativeToolAgentTest test
	@node scripts/evaluate-agent-security.mjs --adversarial-verified

agent-release-real: ## Regenerate and verify the complete Agent across providers, corpus, player needs, and viewports
	@sh scripts/run-complete-agent-release.sh

format: ## Format backend and frontend sources (planned)
	@echo "format is not available yet; formatter configuration is pending."
	@exit 2

backend-test: ## Run backend unit and application tests
	@if [ -f backend/mvnw ]; then \
		(cd backend && ./mvnw test); \
	else \
		echo "backend-test is not available yet; P0-02 is pending."; \
		exit 2; \
	fi

frontend-test: ## Run frontend typecheck, lint, unit tests, and build
	@if [ -f frontend/package.json ]; then \
		(cd frontend && npm run verify); \
	else \
		echo "frontend-test is not available yet; P0-03 is pending."; \
		exit 2; \
	fi

integration-test: ## Run local infrastructure integration smoke tests
	@sh scripts/run-integration-tests.sh

performance-test: ## Run the self-contained local PDF, retrieval, cache, and answer benchmark
	@sh scripts/run-performance-tests.sh

security-test: ## Audit frontend and backend dependencies for known vulnerabilities
	@sh scripts/audit-dependencies.sh

e2e: ## Run Playwright end-to-end tests
	@if [ -f frontend/package.json ]; then \
		(cd frontend && npm run test:e2e); \
	else \
		echo "e2e is not available; the frontend project is missing."; \
		exit 2; \
	fi

verify: ## Verify repository structure, Compose config, backend, and frontend
	@sh scripts/verify-foundation.sh
	@node --test scripts/verify-documentation.test.mjs
	@sh scripts/verify-compose.sh config
	@sh scripts/run-deployment.sh config
	@sh scripts/verify-architecture.sh
	@node --test scripts/evaluate-product.test.mjs
	@node --test scripts/preflight-public-rulebook.test.mjs
	@node --test scripts/generate-public-corpus-entry.test.mjs
	@node --test scripts/evaluate-agent-baseline.test.mjs
	@node --test scripts/evaluate-agent-security.test.mjs
	@node --test scripts/verify-complete-agent-release.test.mjs
	@node --test scripts/probe-model-tool-capabilities.test.mjs
	@node --test scripts/smoke-production-ordinary-user.test.mjs
	@node --test scripts/verify-ci-workflow.test.mjs
	@sh scripts/verify-mockito-agent.sh
	@sh scripts/verify-archunit-imports.sh
	@if [ -f backend/mvnw ]; then \
		(cd backend && ./mvnw verify); \
	else \
		echo "backend verification is not available yet; P0-02 is pending."; \
		exit 2; \
	fi
	@if [ -f frontend/package.json ]; then \
		(cd frontend && npm run verify); \
	else \
		echo "frontend verification is not available yet; P0-03 is pending."; \
		exit 2; \
	fi

compose-up: ## Start and verify local data, messaging, storage, and observability services
	@sh scripts/verify-compose.sh up

compose-down: ## Stop local infrastructure services and retain their data
	@sh scripts/verify-compose.sh down

deployment-up: ## Build and start separate API and worker containers with local dependencies
	@sh scripts/run-deployment.sh up

deployment-down: ## Stop the split API/worker deployment and retain named-volume data
	@sh scripts/run-deployment.sh down

production-up: ## Build and start the HTTPS-ready production Compose topology
	@sh scripts/run-production.sh up

production-down: ## Stop the production Compose topology and retain named-volume data
	@sh scripts/run-production.sh down
