SHELL := /bin/sh

PRODUCT_EVAL_DATASET ?= examples/evaluation/lantern-relay/product-evaluation.json
PRODUCT_EVAL_OUTPUT ?= .local/product-evaluation/latest-report.json

.PHONY: help bootstrap dev dev-split dev-stop demo-data product-eval corpus-preflight format backend-test frontend-test integration-test performance-test security-test e2e verify compose-up compose-down deployment-up deployment-down

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
	@sh scripts/verify-compose.sh config
	@sh scripts/run-deployment.sh config
	@sh scripts/verify-architecture.sh
	@node --test scripts/evaluate-product.test.mjs
	@node --test scripts/preflight-public-rulebook.test.mjs
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
