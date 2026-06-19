# AGENTS.md

Codex entrypoint for `limited-goods-reservation`.

This file is the **current work index**. It may change as the project version changes. Stable version rules live under `docs/`.

---

## Current Status

```text
current_version: v1
current_goal: naive purchase baseline implemented and experiment recorded
next_target: v2 stock strategy comparison
project_type: Spring Boot backend portfolio project
architecture_now: feature-based N-tier baseline
official_verification_path: Docker Compose first
```

Do not start v2 stock strategy work unless the user explicitly asks to start v2 work.

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

- v1 should be a simple feature-based N-tier baseline.
- Do not introduce hexagonal architecture, ports, adapters, Redis, MQ, waiting room, payment worker, or reward allocation in v1.
- v1/v2 core experiments use a single hot product and single product stock.
- External requests use `productId`; `product_stock.id` is an internal DB identifier.
- Use `X-USER-ID` as the simplified test identity.
- Do not add a `users` table in v1.
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
