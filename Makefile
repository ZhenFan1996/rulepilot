SHELL := /bin/sh

.PHONY: help bootstrap dev demo-data format backend-test frontend-test integration-test performance-test security-test e2e verify compose-up compose-down

help: ## Show the available repository commands
	@awk 'BEGIN {FS = ":.*##"; printf "RulePilot commands:\n\n"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  %-20s %s\n", $$1, $$2} END {printf "\n"}' $(MAKEFILE_LIST)

bootstrap: ## Validate the current repository foundation
	@sh scripts/verify-foundation.sh

dev: ## Start backend and frontend development servers
	@set -e; \
		set -a; \
		if [ -f .env ]; then . ./.env; fi; \
		set +a; \
		trap 'kill 0' INT TERM EXIT; \
		(cd backend && ./mvnw spring-boot:run) & \
		(cd frontend && npm run dev -- --host 127.0.0.1) & \
		wait

demo-data: ## Load the self-authored demo rulebook into a running local backend
	@sh scripts/load-demo-data.sh

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
	@sh scripts/verify-architecture.sh
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
