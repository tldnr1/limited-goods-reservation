# AGENTS.md

Codex entrypoint for `limited-goods-reservation`.

This file is the **current work index**. It may change as the project version changes. Stable version rules live under `docs/`.

---

## Current Status

```text
current_version: v2
current_goal: layered stock strategy comparison
next_target: v2 60-run stock strategy benchmark matrix
project_type: Spring Boot backend portfolio project
architecture_now: v1-like layered + stock strategy
official_verification_path: Docker Compose first
```

Do not select the final v2 stock strategy until the 4-strategy benchmark matrix produces measured results.

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

- v2 should keep a v1-like layered structure and isolate only stock deduction behind a small strategy interface.
- v2 official comparison strategies are `naive-rdb`, `rdb-atomic`, `rdb-pessimistic`, and `redis-lua`.
- Redis Lua is an official v2 comparison strategy. Treat Redis `stock:available:{productId}` as the stock decision source of truth for that strategy.
- For Redis Lua, separate stock decision latency from full HTTP latency because the v2 API still saves a DB order synchronously before responding.
- Do not introduce MQ, waiting room, active token, payment worker, or reward allocation in v2.
- v1/v2 core experiments use a single hot product and single product stock.
- External requests use `productId`; `product_stock.id` is an internal DB identifier.
- Use `X-USER-ID` as the simplified test identity.
- Do not add a `users` table in v2.
- Do not create future package placeholders without real classes.
- Run one official strategy per benchmark command. Each strategy uses 100, 500, and 1000 users, each 5 times, before recording the selected v2 path.

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
