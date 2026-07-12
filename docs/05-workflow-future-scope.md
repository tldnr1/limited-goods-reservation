# 05. Workflow Future Scope

This document is the Truth for branch workflow, PR expectations, documentation updates, and future scope.

---

## 1. Branch Types

Default flow:

```text
main <- dev <- feature/*
main <- hotfix/*
experiment/* may be used temporarily for comparison spikes
```

Branch meaning:

```text
main          stable documented baseline
dev           integration branch before main
feature/*     main-path implementation
hotfix/*      urgent correction from main
experiment/*  short-lived comparison implementation
docs/*        documentation-only work
```

---

## 2. Branch and PR Naming

Use hyphenated version numbers in branch names when the version has a dot.

Examples:

```text
docs/v0-documentation-cleanup
feature/v1-naive-purchase-baseline
feature/v2-selected-stock-strategy
experiment/v3-1-token-policy-spike
experiment/v3-2-compensation-spike
experiment/v3-3-payment-timeout-spike
feature/v3-1-waiting-room-active-token
feature/v3-2-reservation-compensation
feature/v3-3-payment-worker
feature/v4-reward-allocation
```

PR title examples:

```text
[v0] Clean up project documents
[v1] Implement naive purchase baseline
[v1] Add k6 oversell baseline scenario
[v2] Compare stock consistency strategies
[v2] Add selected stock strategy
[v3.1] Add waiting room and active token
[v3.2] Add reservation compensation
[v3.3] Add RabbitMQ payment worker
[v4] Add reward allocation MVP
```

Use simple Conventional Commits.

---

## 3. Merge Checklist

Before merging feature work:

```text
version boundary is respected
tests for the version objective pass
relevant docs are updated
excluded scope is not accidentally implemented
```

Before merging experiment output:

```text
comparison question is clear
result is documented
decision is stated
limitations are stated
temporary branch can be deleted after useful code or records are folded into the main path
```

---

## 4. Documentation Update Rule

Use `AGENTS.md` as the current status index. Use docs as version Truth.

Document levels:

```text
README.md              project overview and measured progression
RUNBOOK.md             local execution and experiment reproduction
docs/                  version Truth and current project rules
records/adr/           accepted architecture decisions
records/experiments/   measured experiment evidence
notes/                 local/private scratch notes, gitignored, not Truth
```

Update order when a version changes:

```text
1. Update AGENTS.md current_version/current_goal/next_target.
2. Fill the completed version's Result in docs/01-roadmap.md.
3. Update docs/02-domain-data.md only when domain, ERD, identifiers, tables, Redis keys, or statuses change.
4. Update docs/03-architecture.md only when package structure or dependency direction changes.
5. Update docs/04-verification-experiments.md only when tests, scenarios, metrics, or experiment results change.
6. Update this document only when workflow or future scope changes.
```

Do not duplicate the same rule across multiple docs. Link to the owning doc instead.

---

## 5. Future Scope

These items are intentionally excluded from early versions.

Commerce expansion:

```text
SKU
size/color option
multi-quantity order
cart
shipping address
coupon
saleEvent model
```

Authentication and security:

```text
real signup/login
JWT
role-based admin API
refresh token
rate limit by authenticated user
```

Payment recovery:

```text
duplicate webhook handling
delayed webhook handling
webhook_events table
DLQ
outbox pattern
reconciliation worker
manual review state
```

Messaging and platform:

```text
Kafka
Redis Cluster
Redis Sentinel
Kubernetes
AWS deployment
Prometheus / Grafana dashboard
APM-style tracing
```

AI extension:

```text
abnormal purchase attempt detection
bot-like behavior scoring
LLM-based admin incident summary
traffic anomaly report generation
```

Future scope should not leak into v1-v4 unless the roadmap is explicitly changed.
