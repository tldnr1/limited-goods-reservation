# 04. Verification Experiments

This document is the Truth for tests, k6 scenarios, metrics, and experiment result format.

Version boundaries belong to `docs/01-roadmap.md`.

---

## 1. Verification Principle

Tests and load scenarios should prove the version objective.

Each important test should answer:

```text
What failure or behavior does this prove?
Which version does it belong to?
What metric or assertion shows success?
```

Codex may generate boilerplate tests, but core scenarios should come from this document or be added here before implementation.

---

## 2. Test Stack

```text
JUnit5
AssertJ
Spring Boot Test
Testcontainers when infrastructure integration is needed
k6
Docker Compose
```

Version-level verification should be runnable through Docker Compose. Local IDE or local Java execution may be used for fast feedback.

---

## 3. Core Scenarios

### v0 Smoke

Expected:

```text
Spring Boot context loads
Actuator health endpoint is reachable in Docker Compose
k6 smoke scenario can call the API health endpoint
```

Result:

```text
to be filled when v0 verification is completed
```

### v1 Oversell Reproduction

Given:

```text
single hot product
productId = 1
initial stock = 100
concurrent users = about 1000
naive purchase flow
distinct X-USER-ID per request
```

Expected:

```text
successful order count > 100
oversell_count > 0
```

Result:

```text
Verified on 2026-06-09 through Docker Compose.

Result:
- successful purchase responses: 973
- sold out responses: 27
- unexpected responses: 0
- initial stock: 100
- product_stock.sold_quantity: 97
- orders count: 973
- oversell_count: 873
- order_stock_gap: 876

Detailed record:
- records/experiments/v1-oversell-baseline.md
```

### v2 Stock Strategy Comparison

Given:

```text
same hot product scenario
same initial stock
same request pattern
multiple stock consistency strategies
```

Strategies:

```text
RDB atomic update
RDB pessimistic lock
RDB optimistic lock
Redis distributed lock
Redis Lua
```

Expected:

```text
each strategy records oversell_count
each strategy records success/failure counts
each strategy records latency metrics where practical
selected main path achieves oversell_count = 0
```

Result:

```text
to be filled after v2 comparison
```

### v3.1 Entry Control

Expected:

```text
request without active token is rejected
entry traffic is controlled before stock reservation
waiting queue size is observable
active token issued count is observable
```

Result:

```text
to be filled after v3.1 experiment
```

### v3.2 Payment Delay Isolation

Expected:

```text
API response time is not directly tied to Mock PG delay
payment queue backlog is observable
worker throughput is observable
PG timeout becomes UNKNOWN or retry target
```

Result:

```text
to be filled after v3.2 experiment
```

---

## 4. Common Metrics

k6 metrics:

```text
requests per second
failed request rate
p50 latency
p95 latency
p99 latency
status code distribution
scenario success count
scenario failure count
```

Business metrics:

```text
initial stock
created order count
reserved count
sold count
released count
oversell count
duplicate order count
sold out response count
```

v3+ metrics:

```text
waiting queue size
active token issued count
active token rejected count
payment queue backlog
worker throughput
payment success/failure/timeout count
retry count
```

---

## 5. Scenario Files

Suggested paths:

```text
k6/v0/smoke.js
k6/v1/oversell-baseline.js
k6/v2/stock-strategy-comparison.js
k6/v3-1/waiting-room.js
k6/v3-2/payment-worker-delay.js
```

Each scenario should record:

```text
version
purpose
system setup
initial data
request pattern
expected failure or success
metrics captured
result summary
```

---

## 6. Experiment Policy

Experiments exist to support architectural decisions.

Experiment branches:

```text
should focus on one comparison question
may be incomplete compared to main-path work
do not need complete edge-case handling
do not need to be merged into main
must produce a written result if they influence a decision
```

Store result summaries under:

```text
records/experiments/
```

Experiment document format:

```text
# Experiment Title

## Version

## Question

## Alternatives

## Comparison Criteria

## Setup

## Metrics

## Result Summary

## Decision

## Why This Decision Fits This Project

## Limitations

## Follow-up
```
