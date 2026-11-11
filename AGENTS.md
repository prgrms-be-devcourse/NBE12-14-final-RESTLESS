
# Agent Instructions

## 1. Project

This repository implements an artist fandom event commerce platform.

Core domains:
- Fan meeting: entry applications, winner draw, winner payment
- Ticketing: seat holds and reservations
- Drop: waiting room, inventory reservation, purchase
- Auction: concurrent bidding and closing

Shared capabilities:
- Authentication and authorization
- Orders and payment integration
- Post-commit domain events (Spring application events)
- Observability and AWS deployment

## 2. Source of Truth

Start from docs/README.md (documentation index) and
read only the documentation relevant to the task.

- Product requirements: docs/product/
- Domain invariants: docs/domains/
- System architecture: docs/architecture/
- API and event contracts: docs/contracts/
- Accepted decisions: docs/decisions/
- Testing strategy: docs/testing/
- Operations: docs/operations/

Accepted documents describe intended behavior.
Source code and tests describe current implemented behavior.

If documentation and implementation conflict:
1. Identify and report the discrepancy.
2. Do not silently change business policies.
3. Propose a resolution before changing public contracts
   or critical invariants.

## 3. Required Workflow

Before implementation:
1. Identify the affected domain and modules.
2. Read its requirements, policies, and state machine.
3. Check relevant API contracts and accepted ADRs.
4. Identify affected invariants and failure scenarios.
5. Produce a concise implementation plan.

During implementation:
1. Keep changes within the agreed scope.
2. Preserve module boundaries.
3. Add or update tests for changed behavior.
4. Handle errors and retries explicitly.
5. Do not introduce unnecessary abstractions.

After implementation:
1. Run relevant tests and static checks.
2. Verify affected invariants.
3. Update changed contracts and documentation.
4. Summarize changed files, tests, and remaining risks.
5. Never report tests as passed unless executed.

## 4. Critical Invariants

- Fan meeting confirmed plus payment-pending selections must not exceed capacity.
- A fan meeting payment approved within its deadline must confirm participation.
- A concert seat must not have multiple active owners.
- Drop inventory must never be oversold.
- Duplicate purchase requests must not create duplicate orders.
- Unknown PG results must not be treated as failures.
- Auction closing must produce at most one winner.

Never bypass these constraints to satisfy a test or
simplify implementation.

## 5. Architecture Boundaries

- Prefer the agreed modular monolith architecture.
- Domain modules own their business rules and data access.
- Do not access another domain's repository directly.
- Coordinate cross-domain workflows through defined
  application services or contracts.
- Do not hold database transactions open during PG calls.
- Commit core state (inventory, orders, payments) inside
  the database transaction. Never delegate it to event
  listeners.
- Run follow-up work (e.g. notifications) only in
  AFTER_COMMIT listeners.
- Log and emit a metric on every listener failure.
  Never swallow listener exceptions silently.
- Do not introduce Outbox or Kafka unless an accepted ADR
  says so (see docs/decisions/0001-event-handling.md).
- Avoid unnecessary changes to shared interfaces.

## 6. Verification

For every behavioral change:
- Run focused unit or integration tests.
- Run relevant concurrency tests when changing resource
  allocation, reservation, payment, or bidding logic.
- Validate API/event compatibility when contracts change.
- Run the build and test commands in docs/testing/strategy.md
  before PR review.

Use the actual commands documented in docs/testing/.
Do not invent test results or benchmark numbers.

## 7. Output Location

- Write scratch output (plans, reports, logs, analysis
  notes) only to `.ai-output/`. It is git-ignored.
- Do not create such files anywhere else in the repository.
- Move a result into docs/ only when asked to make it a
  shared document.

## 8. Safety

- Never commit secrets, tokens, or credentials.
- Never run destructive database migrations without review.
- Never edit a migration file that is already merged into
  develop. Add a new migration instead.
- Never load-test a real payment provider.
- Never deploy to production without explicit authorization.
- Do not disable tests or weaken assertions to obtain
  a passing build.

## 9. Pull Requests

Include:
- What changed and why
- Affected domain and invariants
- Test commands and actual results
- Contract or documentation updates
- Remaining risks

Use Conventional Commits:
type(scope): description

