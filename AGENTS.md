# Agent Instructions

This file is a map. It holds the rules that always apply and points to the docs that hold the details.

## 1. Project

Artist fandom event commerce platform. Modular monolith: Spring Boot backend, React frontend.

- Core domains: fan meeting (entry applications, winner draw, winner payment), ticketing (seat holds,
  reservations), drop (waiting room, stock hold, purchase), auction (concurrent bidding, closing).
- Shared modules: authentication and authorization, orders and payment (Toss PG), post-commit domain
  events (Spring application events), observability and AWS deployment.

## 2. Repository Map

| Path | Contents |
|---|---|
| `backend/src/main/java/com/restless/<module>/` | One package per module, layered `api` / `application` / `domain` / `infrastructure` |
| `backend/src/main/resources/db/migration/` | Flyway migrations, the schema source of truth |
| `backend/src/test/java/com/restless/architecture/` | ArchUnit module boundary tests |
| `frontend/` | React + Vite |
| `docs/` | Requirements, invariants, contracts, decisions. Index: [docs/README.md](docs/README.md) |
| `tools/harness/` | AI implementation and verification tooling ([README](tools/harness/README.md)) |
| `.ai-output/` | Git-ignored scratch output |

A module may have its own `AGENTS.md` (e.g. `backend/src/main/java/com/restless/drop/AGENTS.md`).
Read it before changing that module. Codex started at the repo root does not load it automatically.

## 3. Source of Truth

Start from [docs/README.md](docs/README.md). It lists which docs to read for each kind of task.
Read only what the task needs.

Accepted documents describe intended behavior. Source code and tests describe current behavior.
If they conflict:
1. Identify and report the discrepancy.
2. Do not silently change business policies.
3. Propose a resolution before changing public contracts or critical invariants.

If a policy question is listed in [docs/product/open-questions.md](docs/product/open-questions.md),
do not decide it. If the docs are silent and you must decide, choose the option closest to the docs
and record it as `PROPOSED` in that domain's policy doc in the same PR.

## 4. Workflow

- Before: identify the affected domain and modules; read its requirements, policies, state machine,
  API contracts and accepted ADRs; list the affected invariants and failure scenarios; write a short plan.
- During: keep to the agreed scope; preserve module boundaries; add or update tests for changed
  behavior; handle errors and retries explicitly; do not introduce unnecessary abstractions.
- After: run relevant tests and static checks; verify affected invariants; update changed contracts
  and docs; summarize changed files, tests and remaining risks. Never report tests as passed unless executed.

## 5. Critical Invariants

Never bypass these to satisfy a test or simplify implementation. Exact definitions live in the linked docs.

| Invariant | Definition |
|---|---|
| Fan meeting confirmed plus payment-pending selections must not exceed capacity. | [INV-FM-07](docs/domains/fanmeeting/invariants.md) |
| A fan meeting payment approved within its deadline must confirm participation. | [INV-FM-10](docs/domains/fanmeeting/invariants.md) |
| A concert seat must not have multiple active owners. | [Seat ownership](docs/domains/ticketing/seat-ownership.md) |
| Drop inventory must never be oversold. | [INV-01, INV-02](docs/domains/drop/invariants.md) |
| Duplicate purchase requests must not create duplicate orders. | [Drop INV-07](docs/domains/drop/invariants.md), [INV-FM-01, 13](docs/domains/fanmeeting/invariants.md), [INV-AU-12](docs/domains/auction/invariants.md), [ticketing](docs/domains/ticketing/idempotency.md) |
| Unknown PG results must not be treated as failures. | [Failure handling](docs/operations/failure-handling.md), [Drop INV-10](docs/domains/drop/invariants.md), [INV-FM-11](docs/domains/fanmeeting/invariants.md) |
| Auction closing must produce at most one winner. | [INV-AU-02](docs/domains/auction/invariants.md) |

## 6. Architecture Boundaries

- Prefer the agreed modular monolith architecture.
- Domain modules own their business rules and data access.
- Do not access another domain's repository directly. ArchUnit checks this for every module.
- Coordinate cross-domain workflows through defined application services or contracts.
- Do not hold database transactions open during PG calls.
- Commit core state (inventory, orders, payments) inside the database transaction.
  Never delegate it to event listeners.
- Run follow-up work (e.g. notifications) only in AFTER_COMMIT listeners.
- Log and emit a metric on every listener failure. Never swallow listener exceptions silently.
- Do not introduce Outbox or Kafka unless an accepted ADR says so
  ([ADR-0001](docs/decisions/0001-event-handling.md)).
- Avoid unnecessary changes to shared interfaces.

## 7. Verification

Commands ([docs/testing/strategy.md](docs/testing/strategy.md) is the full list):

| Purpose | Command |
|---|---|
| Backend build and all tests (same as CI) | `cd backend && ./gradlew build` |
| Module boundaries | `cd backend && ./gradlew test --tests 'com.restless.architecture.*'` |
| Frontend build (same as CI) | `cd frontend && npm run build` |

For every behavioral change, run focused unit or integration tests. Run concurrency tests when changing
resource allocation, reservation, payment or bidding logic. Validate API and event compatibility when
contracts change. Before opening a PR, run the backend build (and the frontend build if `frontend/`
changed). Do not invent test results or benchmark numbers.

## 8. Output Location

- Write scratch output (plans, reports, logs, analysis notes) only to `.ai-output/`. It is git-ignored.
  Do not create such files anywhere else in the repository, including the repo root.
- Shared plans and tech debt go to [docs/plans/](docs/plans/README.md). Move other results into
  `docs/` only when asked to make them shared documents.

## 9. Safety

- Never commit secrets, tokens, or credentials.
- Never run destructive database migrations without review.
- Never edit a migration file that is already merged into dev. Add a new migration instead.
- Never load-test a real payment provider.
- Never deploy to production without explicit authorization.
- Do not disable tests or weaken assertions to obtain a passing build.

## 10. Pull Requests

Include: what changed and why; affected domain and invariants; test commands and actual results;
contract or documentation updates; remaining risks.

Use Conventional Commits: `type(scope): description`
