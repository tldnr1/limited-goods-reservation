# AGENTS.md

Codex entrypoint for `limited-goods-reservation`.

This file is the **current work index**. It may change as the project version changes. Stable version rules live under `docs/`.

---

## Current Status

```text
current_version: v3.2
current_goal: v3.2 comparison and project narrative documented
next_target: plan v3.3 payment delay isolation
project_type: Spring Boot backend portfolio project
architecture_now: v3.1 waiting room complete; v3.2 modular monolith transition
official_verification_path: Docker Compose first
```

v3.1 is complete. v3.2 reservation, idempotency, compensation smoke verification, and the first reservation load baseline are implemented.
The first Redis Lua reservation baseline is recorded as evidence that stock-decision-only Redis is not enough after idempotency and reservation truth are added.
The v3.2 Redis front gate vs RDB atomic comparison is implemented, load-tested, and documented.
The root README now presents the measured project progression, while `RUNBOOK.md` owns local execution and experiment reproduction.

---

## Project Intent

This is not a full commerce service.

The project exists to show this progression:

```text
failure reproduction
-> root cause analysis
-> alternative comparison
-> structure selection
-> metric-based validation
-> next version improvement
```

Core problems:

```text
oversell
inventory consistency under concurrency
entry traffic surge
payment delay
reward allocation policy
```

---

## Read This Next

Use this table instead of reading every document.

```text
Need version scope / allowed work?
-> docs/01-roadmap.md

Need domain terms, ERD, identifiers, Redis keys, or status candidates?
-> docs/02-domain-data.md

Need package structure, dependency direction, or architecture by version?
-> docs/03-architecture.md

Need tests, k6 scenarios, metrics, or experiment result format?
-> docs/04-verification-experiments.md

Need branch, PR, documentation update, or future-scope rules?
-> docs/05-workflow-future-scope.md

Need accepted decision history?
-> records/adr/

Need measured experiment evidence?
-> records/experiments/

Need private scratch notes?
-> notes/ (gitignored)
```

Docs are version Truth. Do not rewrite older version intent when a later version starts. Add later-version decisions or fill `Result` sections instead.
Records explain why decisions were made. Notes are local/private scratch and are not project Truth.

---

## Current Guardrails

- The v2 result is recorded under `records/experiments/`; do not reinterpret measured results without a new experiment.
- Keep v2 Redis Lua results as measured evidence, but do not treat the first v3.2 Redis Lua reservation path as the final design target.
- The final v3.2 comparison candidates are redis-frontgate and rdb-atomic.
- v3.1 controls entry traffic before the existing purchase and stock path.
- v3.1 uses Redis ZSET for the waiting queue, active-token keys with TTL, and explicit purchase guard rejection.
- v3.1 default admission policy is hybrid: issue at most `batchSize=20` tokens every 1 second without exceeding `activeCapacity=100`.
- Add waiting room and active token boundaries only when real v3.1 classes are implemented.
- Do not introduce reservation TTL, purchase idempotency, Redis stock compensation, RabbitMQ, payment worker, reward allocation, outbox, or reconciliation worker in v3.1.
- v3.2 handles reservation, idempotency, and simple compensation for the Redis-to-DB dual-write gap.
- v3.2 baseline load results show the current Redis Lua reservation path is not automatically faster than RDB atomic after DB idempotency, duplicate checks, and reservation persistence are added.
- v3.2 Redis front gate is implemented as a minimal front gate and compared with the simpler RDB atomic control path.
- Do not force redis-frontgate into the existing `StockDeductionStrategy.deduct(productId)` abstraction; it needs userId, productId, idempotency key, and active-token state.
- v3.3 handles RabbitMQ payment worker and Mock PG delay isolation.
- Core experiments continue to use a single hot product and single product stock unless the scenario explicitly changes.
- External requests use `productId`; `product_stock.id` is an internal DB identifier.
- Use `X-USER-ID` as the simplified test identity.
- Do not add a `users` table in v3.1.
- Do not create future package placeholders without real classes.

---

## Codex Work Rules

Before changing files:

```text
1. Check current_version and current_goal in this file.
2. Read only the routed docs needed for the task.
3. Keep changes scoped to the requested version.
4. Update docs when behavior, version scope, architecture, data, or verification rules change.
5. Verify with the narrowest useful command.
```

Version update routine:

```text
1. Update current_version/current_goal/next_target in AGENTS.md.
2. Fill the completed version's Result in docs/01-roadmap.md.
3. Update docs/02, docs/03, or docs/04 only if data, architecture, or verification results changed.
4. Update docs/05 only if workflow or future scope changed.
```

---

## Early-Version Out of Scope

Do not add these before the roadmap allows them:

```text
SKU / size / color option
cart
multi-quantity order
shipping
real authentication / JWT
real PG integration
duplicate webhook handling
delayed webhook handling
DLQ
outbox pattern
reconciliation worker
Kafka
Kubernetes
cloud deployment
```
