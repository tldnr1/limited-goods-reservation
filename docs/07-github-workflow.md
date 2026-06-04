# 07. GitHub Workflow

This document defines branch, PR, commit, and documentation workflow.

---

## 1. Branch Types

The repository may start as a local-only git repository. Upstream GitHub remote can be added later when the project owner creates the GitHub repository.

Default flow:

```text
main <- dev <- feature/*
main <- hotfix/*
experiment/* is kept for comparison records and may not be merged
```

```text
main
```

Stable branch. Only merge completed and documented main-path work.

```text
dev
```

Integration branch for normal development before main.

```text
feature/*
```

Main-path implementation branch.

```text
hotfix/*
```

Urgent correction branch from main. Merge back to main and dev when applicable.

```text
experiment/*
```

Comparison or benchmark branch kept for record. Does not have to be merged.

```text
docs/*
```

Documentation branch.

---

## 2. Branch Naming

Use hyphenated version numbers in branch names.

Document version:

```text
v2.2
```

Branch version:

```text
v2-2
```

Examples:

```text
feature/v1-naive-purchase-baseline
hotfix/v1-fix-oversell-metric-query
experiment/v2-1-rdb-atomic-update
experiment/v2-1-rdb-pessimistic-lock
experiment/v2-1-redis-distributed-lock
feature/v2-2-redis-lua-reservation
feature/v2-3-idempotency-one-user-one-product
feature/v2-4-api-scaleout-consistency
feature/v3-1-waiting-room-active-token
experiment/v3-1-rate-limit
feature/v3-2-payment-worker
experiment/v3-2-sync-payment
experiment/v3-2-background-task
feature/v4-reward-allocation
```

---

## 3. PR Title Convention

Use version prefix.

Examples:

```text
[v1] Implement naive purchase baseline
[v1] Add k6 oversell baseline scenario
[v2.1] Compare inventory consistency alternatives
[v2.2] Add Redis Lua reservation
[v2.3] Add idempotency and one-user-one-product rule
[v2.4] Verify API scale-out consistency
[v3.1] Add waiting room and active token
[v3.2] Add RabbitMQ payment worker
[v4] Add reward allocation MVP
```

---

## 4. Commit Convention

Use simple Conventional Commits.

Examples:

```text
feat: add naive purchase flow
test: add oversell baseline scenario
docs: add inventory consistency comparison
refactor: separate reservation use case
fix: prevent duplicate order creation
chore: add docker compose for redis
```

Put version information in PR titles rather than every commit.

---

## 5. PR Template

The repository should include:

```text
.github/pull_request_template.md
```

Required fields:

```text
Version
Purpose
Changes
Tests
Metrics / Result
Not Included
Documentation Updated
```

---

## 6. Experiment Branch Policy

Experiment branches:

```text
may be incomplete
may not be merged
should focus on one comparison question
should produce docs/experiments output
may be kept as a saved implementation record
```

Experiment branches should not expand the project scope.

Experiment results that influence the main path should be summarized in docs/experiments before or instead of merging code.

---

## 7. Merge Policy

Before merging a feature branch:

```text
version boundary is respected
tests for the version objective pass
relevant docs are updated
excluded scope is not accidentally implemented
```

Before merging a hotfix branch:

```text
the fix targets an urgent defect or broken verification path
the fix is merged back to main
the fix is also reflected in dev if dev exists
scope is limited to the correction
```

Before merging experiment outputs:

```text
comparison criteria are clear
result is documented
decision is stated
limitations are stated
```

---

## 8. Documentation Update Policy

Update docs when changing:

```text
version boundaries
architecture rules
domain flow
status model
data model
Redis key namespace
test strategy
experiment decision
```
