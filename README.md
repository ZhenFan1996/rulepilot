# RulePilot

[![CI](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml/badge.svg)](https://github.com/ZhenFan1996/rulepilot/actions/workflows/ci.yml)

RulePilot is a board game assistant that recommends games, finds or imports rulebooks,
creates guided lessons, and answers rules questions with page citations.
Lessons and answers use the selected rulebook as evidence and highlight uncertainty.
Interrupted lessons can resume without losing completed chapters.

## Stack

Java 21 · Spring Boot 4 · Spring Modulith · Spring AI · Vue 3 · TypeScript · Vite  
PostgreSQL/pgvector · Redis · RabbitMQ · MinIO · OpenTelemetry · Prometheus · Tempo · Grafana

## Quick start

Requires Java 21, Node.js 24, Docker with Compose, and GNU Make.

```sh
git clone git@github.com:ZhenFan1996/rulepilot.git
cd rulepilot
cp .env.example .env
make compose-up
make dev
```

- **Web:** <http://127.0.0.1:5173>
- **API health:** <http://127.0.0.1:8080/actuator/health>
- **Grafana:** <http://127.0.0.1:3000>

Local development uses deterministic fake providers by default, with no paid model calls.
Configure real providers and credentials in `.env`; see [.env.example](.env.example) for available settings.
`RULEPILOT_MODELS_STARTUP_ALLOWED_USERS` controls access to startup model credentials.

Load the self-authored CC0 demo rulebook into the running application:

```sh
make demo-data
```

Stop the application and infrastructure, retaining stored data:

```sh
make dev-stop
make compose-down
```

## Development

| Command | Purpose |
| --- | --- |
| `make help` | List available commands |
| `make bootstrap` | Check repository structure |
| `make backend-test` | Run backend unit and application tests |
| `make frontend-test` | Run type checks, lint, unit tests, and build |
| `make integration-test` | Check local infrastructure integrations |
| `make e2e` | Run Playwright user journeys |
| `make verify` | Run pre-merge verification |

```text
backend/    Spring Modulith application
frontend/   Vue web app and PWA
infra/      Containers and observability
scripts/    Development, verification, and operations
examples/   Self-authored demo and evaluation data
```

Deployment images include the local Docling layout model for lesson illustrations.
When running Java directly, install [layout dependencies](backend/requirements-layout.txt) with Python 3.10+,
run `backend/install-layout-model.py <model-directory>`, and set `DOCLING_PYTHON` and
`DOCLING_MODEL_DIRECTORY`. Without this setup, cited text and original rulebook pages remain available.

Model response tracing is opt-in via `MODEL_DEBUG_RECORDING_ENABLED=true`.
Recorded outputs are visible to Grafana viewers and retained by Tempo for 24 hours;
redaction cannot guarantee removal of personal information or document excerpts.

See [PROJECT_MODEL.md](PROJECT_MODEL.md) for product and architecture details and
[AGENTS.md](AGENTS.md) for the engineering contract.
Never commit credentials, `.env`, user uploads, commercial rulebooks, or raw model output.
