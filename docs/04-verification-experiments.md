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
6. Run scripts/v2/run-stock-strategy-matrix.ps1 -Strategy {strategy} -Smoke for a quick strategy check.
7. Run scripts/v2/run-stock-strategy-matrix.ps1 -Strategy {strategy} for each official strategy.
8. Summarize records/experiments/v2-stock-strategy-comparison.csv.
9. Query Prometheus for purchase counters and stock/order timer histograms.
10. Capture the Grafana dashboard for troubleshooting records when a result is worth preserving.
```

v2 official load matrix:

```text
strategies: naive-rdb, rdb-atomic, rdb-pessimistic, redis-lua
loads:      100, 500, 1000 users
repeats:    5 per strategy/load
stock:      initial_quantity = 100
```

Each measured run resets DB order/stock state and Redis state before k6 starts. Each strategy gets one warm-up run that is excluded from official results.
Run one strategy per command so failures and machine variance are easier to isolate.

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
feature/v2 starts with stock.strategy = naive-rdb
the naive-rdb strategy must still reproduce stock/order inconsistency
Redis infrastructure is used by the redis-lua official strategy
stock.strategy is selected from STOCK_STRATEGY and defaults to naive-rdb
```

Strategies:

```text
naive-rdb
rdb-atomic
rdb-pessimistic
redis-lua
```

Expected:

```text
each strategy records oversell_count
each strategy records decision_order_gap
each strategy records success/failure counts
each strategy records HTTP p50/p95/p99 through k6
each strategy records stock decision and order save timers through Micrometer
selected main path achieves oversell_count = 0
```

Failure reasons:

```text
SOLD_OUT
LOCK_TIMEOUT
INJECTED_ORDER_SAVE_FAILURE
UNEXPECTED_FAILURE
```

HTTP status is not the primary comparison key. Use the response body `code`, k6 counters, and Prometheus `reason` label.

Result:

```text
The official matrix completed 60 measured runs:
- four strategies
- 100, 500, and 1000 users
- five repeats per strategy/load

Normal-load correctness:
- naive-rdb reproduced stock/order inconsistency
- rdb-atomic, rdb-pessimistic, and redis-lua recorded oversell_count = 0
- selected redis-lua recorded decision_order_gap = 0 in all official matrix runs

Expansion:
- redis-lua and rdb-atomic each ran at 3000, 5000, and 10000 users, five times
- redis-lua had lower HTTP tail latency at every expanded load and no unexpected responses

Failure injection:
- rdb-atomic: stock_decision_count = 100, order_count = 100, gap = 0
- redis-lua: stock_decision_count = 100, order_count = 90, gap = -10

Decision:
- redis-lua is the v3-oriented main path
- rdb-atomic remains the control baseline

Records:
- records/experiments/v2-stock-strategy-comparison.md
- records/experiments/v2-stock-strategy-expansion-rerun.md
- records/experiments/v2-stock-failure-injection.md
```

### v3.1 Entry Control

Question:

```text
Does a waiting room with active tokens reduce burst pressure on the purchase path?
```

Alternatives:

```text
direct access
fixed batch admission
hybrid admission
```

Expected:

```text
request without active token is rejected
duplicate enter does not create duplicate queue entries
entry traffic is controlled before stock decision
waiting queue size is observable
active token issued count is observable
active token rejected count is observable
purchase path attempt count is lower than direct access under the same burst shape
HTTP p95/p99 and unexpected responses are compared across alternatives
```

Result:

```text
Completed on 2026-07-07.

The measured v3.1 matrix confirmed:
- direct access reaches the purchase path with all burst users
- fixed batch admission reduces purchase path attempts by admitting users in controlled batches
- hybrid admission behaves like fixed batch when activeCapacity is above expected active holders
- hybrid admission becomes stricter when activeCapacity is below expected active holders
- not_admitted_within_window is a controlled waiting-room outcome, not an unexpected failure
- unexpected_responses = 0 in all measured rows
- oversell_count = 0 in all measured rows
- decision_order_gap = 0 in all measured rows

Primary records:
- records/experiments/v3-1-entry-control.md
- records/experiments/v3-1-entry-control-initial.md
```

### v3.2 Reservation / Idempotency / Compensation

Question:

```text
Can the Redis Lua stock decision path recover from DB reservation persistence failure?
```

Alternatives:

```text
v2 redis-lua without compensation
redis-lua with synchronous reservation and immediate compensation
rdb-atomic control baseline
```

Expected:

```text
Redis stock decision count and DB reservation count stay aligned under normal load
failure injection after Redis deduction is compensated or recorded as recoverable
duplicate request retry does not create duplicate active reservations
idempotency hit count is observable
compensation success/failure count is observable
```

Result:

```text
to be filled after v3.2 experiment
```

### v3.3 Payment Delay Isolation

Expected:

```text
API response time is not directly tied to Mock PG delay
payment queue backlog is observable
worker throughput is observable
PG timeout becomes UNKNOWN or retry target
```

Result:

```text
to be filled after v3.3 experiment
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
active token current count
duplicate waiting enter count
purchase guard rejection count
reservation count
idempotency hit count
compensation success/failure count
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
stock.decision.duration{strategy}
order.save.duration{strategy}
```

v2 Redis Lua defaults:

```text
Redis stock key: stock:available:{productId}
The benchmark runner must initialize stock:available:1 to 100 before each redis-lua run.
Redis stock deduction followed by synchronous DB order persistence is a dual-write flow; record this limitation.
Do not inject DB failure in the default v2 comparison.
For redis-lua, stock_decision_count = initial_stock - redis_available.
For RDB strategies, stock_decision_count = product_stock.sold_quantity.
For every official strategy, decision_order_gap = order_count - stock_decision_count.
```

---

## 5. Scenario Files

Suggested paths:

```text
k6/v0/smoke.js
k6/v1/oversell-baseline.js
k6/v2/stock-strategy-baseline.js
k6/v3-1/waiting-room.js
k6/v3-1/waiting-room-bypass.js
k6/v3-1/direct-purchase.js
k6/v3-2/reservation-compensation.js
k6/v3-3/payment-worker-delay.js
```

Version runners:

```text
scripts/v2/run-stock-strategy-matrix.ps1
scripts/v2/run-stock-failure-injection-matrix.ps1
scripts/v3-1/run-entry-control-matrix.ps1
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
