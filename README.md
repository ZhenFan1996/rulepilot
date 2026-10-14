# RulePilot

[![CI](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml/badge.svg)](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml)

RulePilot is an evidence-first board game assistant. It recommends games based on player preferences, binds a trusted
rulebook to the selected game, generates lessons with page citations to help players get started at the table, and
answers follow-up questions using the active rulebook. Recommendation, rulebook acquisition, teaching, and Q&A form
one continuous experience, with each outcome able to succeed and recover independently.

See [PROJECT_MODEL.md](PROJECT_MODEL.md) for the product logic, production architecture, technology choices, failure
boundaries, and guidance for explaining the project in interviews. See [AGENTS.md](AGENTS.md) for the engineering contract.

## Technology stack

Java 21, Spring Boot 4, Spring Modulith, Spring AI, Vue 3, TypeScript, Vite, PostgreSQL/pgvector,
Redis, RabbitMQ, MinIO, OpenTelemetry, Prometheus, Tempo, Grafana, JUnit/Testcontainers, Vitest, and
Playwright.

## Local development

Requires Java 21, Node.js 24, Docker, Docker Compose, and GNU Make.

```sh
git clone git@github.com:ZhenFan1996/rulepilot.git
cd rulepilot
cp .env.example .env
make compose-up
make dev
```

Open the web app at <http://127.0.0.1:5173>, check backend health at <http://127.0.0.1:8080/actuator/health>, and access
Grafana at <http://127.0.0.1:3000>. The default configuration uses a deterministic fake provider and does not call paid
models. When a real provider is enabled, if an older `.env` does not declare `RULEPILOT_MODELS_STARTUP_ALLOWED_USERS`,
the local launcher grants access to startup credentials only to the configured local player and demo administrator
accounts. An explicit setting, including an empty value, always takes precedence. Keep real credentials in `.env` and
never commit them.

Load the project's self-authored CC0 demo rulebook:

```sh
make demo-data
```

Stop the development processes and local infrastructure:

```sh
make dev-stop
make compose-down
```

MinIO and `mc` are built from pinned official commits, with versions declared in `infra/minio/Dockerfile`. The images
retain the corresponding source archives and licenses. Compose keeps the original image aliases but builds locally
instead of pulling upstream images that are no longer available. Production application deployments only check
existing storage and do not replace it. Update storage images as a separate maintenance operation: verify object reads
and writes first, and retain the original images and data volumes.

## Common commands

| Command | Purpose |
| --- | --- |
| `make help` | List all available commands |
| `make bootstrap` | Check the repository foundation |
| `make backend-test` | Run backend unit, application, and integration contract tests |
| `make frontend-test` | Run TypeScript checks, lint, Vitest, and build |
| `make e2e` | Run Playwright user journeys |
| `make verify` | Run unified verification before merging |

```text
backend/    Spring Modulith modular monolith
frontend/   Vue 3 web app / PWA
infra/      Compose and observability configuration
scripts/    Development, verification, evaluation, and production operations
examples/   Self-authored demo and evaluation data
```

Do not commit credentials, `.env`, user uploads, actual commercial rulebooks, or raw generative model data.
