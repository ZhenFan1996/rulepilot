# RulePilot engineering contract

## Outcome

Build a RulePilot that works at a real table: natural answers, correct citations, explicit uncertainty, bounded
latency, recoverable long-running work, and a development loop that stays easy to change. Current code and measured
behavior outrank historical plans.

## Work from evidence

1. Inspect the nearest production path and one concrete failure, trace, screenshot, or real response.
2. Name the user outcome and the earliest component that owns it.
3. Establish a before baseline, make the smallest complete change, and run the closest meaningful test.
4. Run broad verification once at the publish boundary. Record transient plans and evidence in the task or PR.

Use current evidence only when relevant:

- Runtime or latency diagnosis: Grafana first to bound service and time, then Tempo for the exact request path.
- Version-sensitive Spring, Vue, Vite, Playwright, or provider behavior: Context7 with generic questions and no source,
  credentials, uploads, copyrighted text, or raw model output.
- Repository and CI inspection: the GitHub connector; PR creation, merge, and workflow writes: authenticated `gh`.
  Push over SSH.
- Page state or authenticated interaction: Browser or Chrome; keep Playwright as reproducible acceptance evidence.
- If a relevant tool is unavailable, report that fact and use the narrowest trustworthy fallback.

## Product boundaries

Recommendation, rulebook acquisition, teaching, and rule Q&A are independent outcomes:

- Recommendation uses the player's typed preferences and verified game data.
- Rulebook acquisition starts only after a game is selected and may use a public source or the player's document.
- Teaching uses the selected, identity-bound rulebook.
- Q&A uses evidence from the active rulebook or lesson.

The public library is an optional shortcut, never a prerequisite for recommendation or Q&A. A separately recoverable
or optional stage cannot turn an already useful outcome into failure. Join stages in one blocking journey only when
the product must commit or fail them atomically.

## Architecture

- Keep the Spring Modulith modular monolith. Top-level packages are business modules.
- Domain code is pure Java. Application services own use cases and transactions. External systems use ports/adapters.
- Modules do not access another module's repository or persistence entity; non-transactional crossings use events.
- A Flyway version that has run in any shared environment is immutable. Restore its deployed checksum and express
  every correction, data move, or compatibility bridge in a new forward migration; never use repair to bless drift.
- Vue 3 and strict TypeScript remain mobile-first and keyboard accessible. Server state uses Vue Query; Pinia is only
  for genuine client-global state.
- Do not add a microservice, framework, generic abstraction, or compatibility layer without replacing more complexity
  than it creates.

## AI boundary

- Free-form model or user prose is player-facing text, never business routing input. Intent, identity, preferences,
  quantities, evidence, tool choice, and workflow state must arrive through validated typed tool arguments.
- Model output is untrusted until schema, evidence identity, ownership, and hard facts pass deterministic validation.
  No rule claim is published without evidence.
- Let the model write natural prose. Do not regex-rewrite or template away a valid answer.
- Publish supported portions, localize uncertainty, and ask at most one useful clarification.
- Prefer one capable model call with relevant evidence. Add a model stage only for a distinct, measured responsibility;
  an optional reviewer or visual enrichment may not erase validated content.
- A replay diagnoses a defect but may not add sample vocabulary, aliases, prompt clauses, schemas, expected answers, or
  special routes to production. Deterministic heuristics must express input-independent invariants.
- Page images are first-class evidence. OCR is optional and fail-open for usable page evidence.
- For durable asynchronous assistant runs, persist token usage, active-work deadlines, tool allow-lists,
  cancellation, and genuine resource boundaries. A workload-derived token estimate for incrementally published work
  such as teaching is capacity telemetry, not a correctness or completion gate: exceeding it must not erase or stop
  validated chapters, and a retry must reuse completed units while continuing missing ones. Hard provider context,
  quota, deadline, cancellation, and admission boundaries may still localize or stop new work. Step and tool-call
  safety ceilings must be derived from an enforced resource envelope, never from a hand-written workflow length;
  model-call counts remain observational. Never expose private reasoning, prompts, credentials, or sensitive
  parameters. Real or paid models are opt-in and never run in normal CI.
- A synchronous adaptive ReAct loop whose external calls all share one measured wall-clock deadline may rely on that
  deadline, provider context/output limits, account quota, cancellation, typed tool bounds, and state-based
  no-progress detection. Do not add an unpersisted cumulative token ledger merely to manufacture step/tool ceilings;
  never charge the same prompt, tool arguments, or observation more than once.
- Never use a hand-written fixed count, character, model-call, action, or retry ceiling as a product-correctness gate.
  A resource bound is allowed only when derived from a provider, platform, database, or security constraint, or from a
  measured resource envelope, with its owner and evidence documented. Prefer adaptive chunking, concurrency
  backpressure, and workload-derived admission; reaching capacity must preserve validated work and localize
  degradation. Persisted token, deadline, and derived step/tool-call budgets above remain resource controls, not
  workflow-completion criteria.
- Structured-output validation is feedback to the same Agent, not permission for application-authored repair. On a
  rejected candidate, preserve the complete candidate exactly once in the same conversation, then return the exact
  validation error, original schema, and allowed identities; let the Agent conditionally generate a new complete
  object. Do not duplicate the candidate in a correction message, crop it, parse prose patches, splice fields, or
  force a repair call when the first candidate is valid. `recommend_games` is the terminal exception: consume it
  once, publish its independently verified subset while omitting unsupported optional prose, or return a typed
  publication failure when no verified selection remains. Never turn that terminal action into another model round.

## Complexity and retirement

- Before adding a field, stage, condition, validator, fallback, prompt rule, test, or compatibility branch, name the
  existing responsibility it replaces. If it replaces nothing, stop and simplify the design.
- One responsibility has one owner. Optional enrichment, lifecycle polling, retry policy, and publication decisions
  must not be implemented independently in several components.
- Retire a protocol in one change: emitter, consumers, state, configuration, prompt, copy, fixtures, and canaries.
  Compatibility requires evidence of live persisted data and an explicit removal event.
- A small feature that increases production LOC, test LOC, protocol count, or stage count without deleting an equal or
  larger responsibility requires architecture review.
- After three reasoned fixes fail, stop adding conditions and reassess the product boundary, owner, contract, and
  acceptance check.

## Tests

Every test has one primary risk owner:

1. Domain invariant.
2. Application contract.
3. Adapter or integration contract.
4. User journey or production canary.

If a distinct production failure cannot be named, delete the test. Own a risk at the earliest stable boundary; higher
layers prove wiring, not the same permutations, mock choreography, source shape, copy, or helper implementation.
Journey tests cover one independently recoverable outcome. Delete tests with the protocol they exercised. A skipped
test needs a named opt-in command and live release risk or it is retired.
Do not add tests whose only purpose is to freeze an arbitrary numeric ceiling, call count, operation string, prompt
wording, or retry choreography. Test the user-visible completion, recovery, evidence, or resource-safety outcome.

Use focused unit/contract tests while editing. PostgreSQL uses Testcontainers, not H2; Redis Lua, MQ idempotency, and
database concurrency use integration tests. Run Modulith, ArchUnit, full Playwright journeys, and the complete suite at
the release boundary. Never encode current output as expected behavior without an independent quality or safety claim.

## Documentation and delivery

Tracked Markdown states current product or operating truth, not work history. Keep `README.md` as the operator entry
point and this file as the engineering contract. Plans, measurements, incident timelines, learning cards, matrices,
snapshots, and archives belong in the task, PR, or an external archive.

Use repository commands: `make bootstrap`, `make dev`, focused `make backend-test` / `make frontend-test`,
`make integration-test`, `make e2e`, and one `make verify` before merge. One logical task gets one Conventional
Commit. Never force-push, rewrite history, commit secrets or user/copyrighted data, or destructively clean outside scope.

Stop for user direction when a change is irreversible, needs a new credential or paid resource, changes authentication,
storage, framework, or major architecture, has two materially different product choices, or exposes a security,
privacy, or copyright risk.

Done means the user outcome holds, the closest tests and release verification pass, architecture boundaries remain
intact, and before/after behavior, latency, complexity, CI, deployment, and production evidence are recorded in the
task or PR.
