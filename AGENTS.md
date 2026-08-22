# AGENTS.md

## Mission
Build a RulePilot that is useful at a real table. Correct citations and hard boundaries matter, but so do clear,
natural answers, low latency, and a development loop short enough to improve the product quickly. Existing design
documents record prior decisions; they are context to verify against current code and observed behavior, not product
truth that must be preserved when evidence shows a worse experience.

## Start with the problem, not the document stack

1. Read this file and inspect the nearest source and tests for the affected path.
2. Reproduce or inspect one concrete failure, trace, screenshot, or real response.
3. Read only the document sections needed for a hard boundary or cross-module contract.
4. Use `docs/roadmap/EXECUTION_STATE.md` to coordinate the logical work item; do not maintain documentation for every
   inner-loop edit.

Read the full blueprint, ADRs, or roadmap only for architecture or product-scope changes. Read AI, retrieval, or UX
guides selectively when that subsystem changes. If a document conflicts with a measured user outcome, update it with
the implementation instead of adding another workaround.

## MCP usage

For every task, decide which current external or runtime evidence is relevant before implementation. When one of the
following routes applies, use that MCP or connected plugin before reaching a conclusion; do not call an irrelevant MCP
merely to satisfy a checklist.

- For a runtime failure, latency regression, resource problem, or production-like diagnosis, use
  `rulepilot_grafana` first to bound the metric, service, and time window, then use `rulepilot_tempo` when an exact
  request path or span relationship can identify the cause. Record the PromQL/TraceQL, time window, and relevant trace
  IDs outside Git with the other diagnostic evidence.
- For current Spring, Vue, Vite, Playwright, provider SDK, or other library behavior that may have changed, use
  `rulepilot_context7` before relying on recalled APIs. Send only a generic library name and documentation question;
  never send source code, credentials, personal data, user uploads, raw model output, or copyrighted rulebook text.
- For repository, PR, Issue, review, or CI state, reuse the connected GitHub plugin rather than adding another GitHub
  MCP or treating stale local metadata as authoritative.
- The installed GitHub connector is read-only for this repository: its PR-write endpoint returns GitHub integration
  scope 403. Use it for inspection, but use the already authenticated `gh` CLI directly for PR creation, merge, and
  workflow writes; do not probe connector writes before every publish. Push local commits over SSH because HTTPS Git
  transport is unreliable in this workspace.
- For actual page state, browser interaction, accessibility, or authenticated UI behavior, use the connected Browser
  or Chrome capability for inspection and keep project Playwright tests as the reproducible acceptance evidence.
- If a relevant server is missing or unhealthy, check `codex mcp list` (or `/mcp` in the TUI), then run its focused
  smoke command or `make mcp-smoke`. Start local observability with `make compose-up` and provision the Grafana Viewer
  credential with `make mcp-grafana-setup` only when that task needs live observability. Report an unavailable MCP and
  use the narrowest trustworthy fallback; never claim live MCP evidence from configuration alone.

The project MCP servers remain optional so offline documentation, unit tests, and unrelated code work can start
without Grafana, Tempo, or remote Context7. Do not add raw database, Redis, object-storage, broker, filesystem, or
container-control MCP access without a separate threat-modelled work item and evidence that application ports,
observability, and repository commands cannot meet the need.

## Fast execution loop

- Establish a failing or before baseline, then implement the smallest complete behavior change.
- Avoid another adapter, policy, or abstraction unless it removes more complexity than it adds.
- Run the closest meaningful tests while editing. A useful test would fail for the reported regression and pass for
  the right reason; tautological tests and tests of constants are not evidence.
- For AI behavior, use one representative opt-in real-model canary when paid API use is authorized. Record raw model
  output, published output, model/tool-call count, and wall latency outside Git.
- Run broader module and architecture checks after the focused slice is stable. Run `make verify` once before the
  merge/release boundary, not after every edit.
- Update execution state and durable documentation after behavior stabilizes, then publish one logical change.
- Parallel workstreams are allowed when requested and their file ownership does not overlap.

## Architecture rules
- Modular monolith with Spring Modulith.
- Top-level packages are business modules, not technical layers.
- Domain code is pure Java and must not depend on Spring, JPA, Redis, HTTP, MQ, or model SDKs.
- Application services own use cases and transaction boundaries.
- External systems are reached through ports and adapters.
- Modules must not access another module's repository or persistence entity.
- Use domain events for non-transactional cross-module reactions.
- Do not add a microservice without a new ADR and user approval.
- Prefer deleting duplicate stages and collapsing responsibilities over adding a new framework.
- Optional visual, critic, localization, or audit enrichment must not erase already validated useful content.

## Clean code rules
- Prefer explicit business names over generic Service/Manager/Util names.
- Keep methods at one abstraction level.
- Use immutable values and records where appropriate.
- Do not create speculative interfaces or generic frameworks.
- Do not put business logic in controllers, JPA entities, or Redis adapters.
- Do not catch broad exceptions without a clear boundary policy.
- Comments explain why, not what.
- Refactor duplication only after the shared concept is stable.

## AI rules
- Never infer intent, entities, preferences, quantities, tool choice, evidence, or workflow state by matching,
  splitting, truncating, or otherwise parsing a model's free-form prose or the user's natural-language message.
  When application behavior needs one of those values, require the Agent to return it through a typed JSON tool
  argument with a documented schema, then validate only the schema, value range, evidence ID, entity identity, and
  ownership boundary. Free-form model text is player-facing output only and must never be an input to business
  routing. Protocol parsing such as JSON decoding, SSE framing, enum dispatch, and bounded display truncation is not
  semantic interpretation and remains allowed.
- Let the model write useful, natural prose. Guardrails validate schema, evidence identity, hard numerical facts, tool
  parameters, and publication boundaries; they do not regex-rewrite or template away a good answer.
- LLM output is untrusted until schema and citation validation pass. No answer may claim a rule without evidence.
- Answer supported portions. Localize an unsupported portion, state uncertainty plainly, and ask at most one useful
  clarification instead of rejecting the whole turn.
- Keep an additional model review on the synchronous path only when before/after evaluation proves material quality
  gain worth its latency and failure mode. Reviewer failure must not destroy a candidate that already passed the
  deterministic publication boundary.
- Prefer one capable model call with relevant evidence over chains of interpretation, generation, criticism,
  confirmation, repair, and re-criticism. Add a stage only for a distinct measured responsibility.
- Real-corpus failures become sanitized evaluation cases, not production vocabulary. Do not add a game title, component, role, mechanic label, page number, or answer-bearing synonym to production decision logic merely to repair one replay.
- A deterministic AI heuristic must state a game-independent invariant and pass cross-rulebook positive and negative tests. Prefer terminology and rule relationships derived from the active document.
- The model may use only allow-listed tools with validated parameters.
- Stream player-safe agent replies, tool-result summaries, evidence decisions, failure reasons, retries, and next
  actions so long-running work remains understandable. Do not expose private model reasoning, internal prompts,
  credentials, or sensitive tool parameters.
- Enforce step, token, timeout, and tool-call budgets.
- Do not call paid or real external models in normal CI.

## No patch-style fixes
- Do not add a special case merely to make one observed failure or test pass. Trace the behavior to its earliest general root cause and repair the owning abstraction or failure boundary.
- A production rule must express a domain invariant that remains valid across inputs, locales, providers, and examples. If it cannot be explained without naming the failing sample, it does not belong in production logic.
- Prefer removing obsolete stages, duplicated responsibilities, and accidental constraints over wrapping them in another condition, fallback, validator, or compatibility layer.
- Prove a fix with independent positive and negative cases, including at least one case unlike the original failure. Tests that only preserve a known output or implementation detail are insufficient.
- When three well-reasoned attempts still fail, stop adding conditions. Reassess the requirement, architecture, tool contract, and acceptance check before changing code again.

## Frontend rules
- Vue 3, TypeScript strict, Vite, Tailwind, shadcn-vue.
- Use Vue Query for server state; Pinia only for true client-global state.
- Cover loading, empty, error, success, and disabled states.
- Mobile-first and keyboard accessible.
- Reuse the established visual language, but change stale UX guidance when a focused product check demonstrates a
  better experience. Never animate fake progress when the backend can expose real activity.

## Testing rules
- The inner loop is a changed-class test plus the narrowest meaningful contract or integration test.
- Domain tests do not start Spring.
- PostgreSQL behavior is tested with Testcontainers, not H2.
- Redis Lua, MQ idempotency, and database concurrency need integration tests.
- Run Spring Modulith, ArchUnit, full Playwright journeys, and the complete suite at the integration/release boundary.
- Ordinary copy or component changes start with focused Vitest, not a deployed journey.
- Do not weaken or delete a test merely to make CI green. Heavy or redundant tests may be split, retired, or moved to
  a scheduled/release suite after a faster test or canary proves the same material risk.
- Never encode current implementation output as the expected answer without an independent quality or boundary claim.

## Documentation and interview learning

Document durable architecture decisions, surprising tradeoffs, verification evidence, and operational recovery. Do not
repeat the same narrative across several documents for every small edit. Update one learning note for the completed
logical workstream; update the interview coverage matrix only when new code genuinely backs a new topic.

## Commands
Prefer repository commands for broad gates, but use direct focused commands during the inner loop when they are
materially faster:
- `make bootstrap`
- `make dev`
- `make format`
- `make backend-test`
- `make frontend-test`
- `make integration-test`
- `make e2e`
- `make verify`
- `make compose-up`
- `make compose-down`

If a repeatable fast path is missing, add a named repository command rather than relying on an undocumented incantation.

## Git rules
- One logical task per commit.
- Conventional Commits.
- Never force-push or rewrite history.
- Never commit secrets, `.env`, real copyrighted rulebooks, generated model data, or user uploads.
- Do not perform destructive cleanup outside the task scope.

## Stop and ask when
- A change is destructive or irreversible.
- A real credential, paid resource, or external account is required and the user has not already authorized it.
- Authentication, database, broker, frontend framework, or major architecture must change.
- Product behavior has two materially different valid choices.
- The same quality gate still fails after three well-reasoned fixes.
- A security, privacy, or copyright risk is discovered.

## Definition of done
A logical workstream is done when its user-visible acceptance criteria hold, the closest meaningful tests pass, AI
changes have before/after quality and latency evidence, required architecture boundaries remain intact, and the
execution-state evidence is updated. Full verification and release evidence are required once at the unified publish
boundary.
