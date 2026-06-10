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

v2 monitoring:

```text
Prometheus scrapes Spring Boot Actuator metrics from /actuator/prometheus.
k6 writes load-test metrics to Prometheus remote write during Compose load-test runs.
Grafana provisions the v2 Stock Strategy Overview dashboard from monitoring/grafana/dashboards/.
Screenshots for troubleshooting records should use the Grafana dashboard plus the DB verification query result.
```

v2 common experiment runbook:

```text
1. docker compose build api
2. docker compose up -d --force-recreate api redis prometheus grafana
3. Check API health: http://localhost:8080/actuator/health
4. Check Prometheus target: up{job="api"} == 1
5. Check Grafana dashboard uid: limited-goods-v2-stock
6. docker compose --profile load-test up --force-recreate k6
7. Run the DB verification query for successful_order_count, oversell_count, and order_stock_gap.
8. Query Prometheus for purchase_attempts_total, purchase_success_total, and purchase_failure_total.
9. Capture the Grafana dashboard for troubleshooting records when a result is worth preserving.
```

v2 load steps:

```text
smoke:    VUS=10   ITERATIONS=10
medium:   VUS=100  ITERATIONS=100
baseline: VUS=1000 ITERATIONS=1000
```

Each strategy should record at least one baseline result.

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

Foundation baseline:

```text
feature/v2 starts with stock_strategy = naive-rdb
the naive-rdb adapter must still reproduce oversell
Redis infrastructure can be present, but the default purchase flow must not require Redis
stock.strategy is selected from STOCK_STRATEGY and defaults to naive-rdb
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

Failure reasons:

```text
SOLD_OUT
OPTIMISTIC_CONFLICT
LOCK_BUSY
LOCK_TIMEOUT
UNEXPECTED_FAILURE
```

HTTP status is not the primary comparison key. Use the response body `code`, k6 counters, and Prometheus `reason` label.

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

v2 foundation custom metrics:

```text
purchase.attempts{strategy}
purchase.success{strategy}
purchase.failure{strategy,reason}
```

v2 Redis experiment defaults:

```text
Redis stock key: stock:available:{productId}
Redis Lua / Redis lock branches must initialize stock:available:1 to 100 before the baseline run.
Redis stock deduction followed by DB order persistence is a dual-write flow; record this limitation.
Do not inject DB failure in the default v2 comparison.
```

---

## 5. Scenario Files

Suggested paths:

```text
k6/v0/smoke.js
k6/v1/oversell-baseline.js
k6/v2/stock-strategy-baseline.js
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
