# 01. Roadmap

This document is the version-boundary Truth.

Older version intent should remain stable. When a version is completed, fill its `Result`. When a later version changes direction, add that decision to the later version instead of rewriting the earlier version's intent.

Each version uses:

```text
Goal
Architecture Level
Allowed
Forbidden
Success Metrics
Result
```

---

## Version Summary

```text
v0: documentation and runnable skeleton
v1: naive purchase baseline with feature-based N-tier
v2: stock strategy comparison with v1-like layered strategy
v3.1: waiting room + active token
v3.2: reservation + idempotency + compensation
v3.3: RabbitMQ + payment worker
v4: reward allocation policy
v5+: advanced scope
```

---

## v0. Documentation / Project Baseline

### Goal

Prepare project documents and a runnable Spring Boot skeleton before business implementation.

### Architecture Level

```text
runnable skeleton only
no business packages
no future package placeholders
```

### Allowed

```text
AGENTS.md
docs
README
PR template
Spring Boot main class
Gradle wrapper
Dockerfile
Docker Compose API + PostgreSQL + k6 smoke setup
Actuator / Micrometer endpoints
```

### Forbidden

```text
business APIs
purchase flow
reservation flow
payment flow
reward allocation
future package placeholders without real classes
```

### Success Metrics

```text
Codex can identify current version and next docs from AGENTS.md.
Version boundaries are explicit.
Spring Boot skeleton can be verified without business features.
Docker Compose remains the official local verification direction.
```

### Result

```text
docs reduced to 5 Truth documents.
AGENTS.md became the current Codex work index and routing table.
README became a human quickstart.
records/adr and records/experiments were prepared for decision and evidence history.
notes/ was gitignored for local/private scratch notes.
empty future package placeholders were removed.
Docker Compose verification passed:
- docker compose build api
- API health endpoint returned UP
- k6 v0 smoke check passed

v1 naive purchase baseline is ready to start.
```

---

## v1. Naive Purchase Baseline

### Goal

Reproduce oversell or inventory inconsistency with a deliberately simple purchase flow.

### Architecture Level

```text
feature-based N-tier
controller -> service -> repository -> database
```

### Allowed

```text
Spring Boot API
PostgreSQL
JPA
Product
ProductStock
Order
feature-based packages
k6 oversell baseline scenario
database verification query
```

### Forbidden

```text
Redis
Lua Script
Reservation
Idempotency
one-user-one-product rule beyond test identity
Waiting Room
Active Token
RabbitMQ
Payment Worker
Reward allocation
Hexagonal architecture
port/adapter abstraction
stock strategy abstraction
users table
```

### Success Metrics

```text
initial stock = 100
concurrent users = about 1000
successful order count > 100
oversell_count > 0
```

### Result

```text
v1 feature-based N-tier baseline was implemented:
- POST /api/v1/purchases
- X-USER-ID request header as scalar test identity
- productId-based external request
- products, product_stock, orders tables
- productId=1 seed data with initial_quantity=100
- deliberately naive RDB read-check-write stock update

Docker Compose verification passed:
- docker compose build api
- API health endpoint returned UP
- docker compose --profile load-test up --force-recreate k6

v1 oversell result:
- concurrent users / iterations: 1000 / 1000
- successful purchase responses: 973
- sold out responses: 27
- unexpected responses: 0
- DB initial_quantity: 100
- DB sold_quantity: 97
- DB successful_order_count: 973
- DB oversell_count: 873
- DB order_stock_gap: 876

Experiment record:
- records/experiments/v1-oversell-baseline.md
```

---

## v2. Stock Strategy Comparison

### Goal

Compare stock consistency strategies and select the main path for preventing oversell.

### Architecture Level

```text
v1-like layered architecture with stock strategy interface
controller -> service -> repository remains easy to follow
```

### Allowed

```text
RDB atomic update
RDB pessimistic lock
Redis Lua
stock strategy interface
Docker Compose benchmark matrix
Prometheus / Grafana strategy metrics
```

### Forbidden

```text
Waiting Room
Active Token
RabbitMQ
Payment Worker
Reward allocation
full reconciliation
outbox pattern
Kafka
MSA-style service split
```

### Success Metrics

```text
alternatives are compared with the same scenario shape
official comparison covers naive-rdb, rdb-atomic, rdb-pessimistic, and redis-lua
100, 500, and 1000 users are tested 5 times per strategy
selected main path is documented
selected main path achieves oversell_count = 0
decision_order_gap = 0 for selected non-naive strategy
```

### Result

```text
v2 kept the v1-like layered purchase flow and isolated stock deduction behind StockDeductionStrategy.

The official 60-run matrix completed:
- strategies: naive-rdb, rdb-atomic, rdb-pessimistic, redis-lua
- users: 100, 500, 1000
- repeats: 5 per strategy/load

naive-rdb reproduced lost update and stock/order inconsistency.
rdb-atomic and rdb-pessimistic preserved oversell_count = 0 and decision_order_gap = 0.
redis-lua also preserved oversell_count = 0 and decision_order_gap = 0 under normal load.

The 3000/5000/10000-user expansion favored redis-lua for HTTP tail latency and response stability.
Failure injection after stock decision showed rdb-atomic gap = 0 and redis-lua gap = -10.

Decision:
- use redis-lua as the v3-oriented main path
- keep rdb-atomic as the control baseline
- design v3 compensation/reservation/reconciliation around the Redis-to-DB dual-write gap

Evidence:
- records/experiments/v2-stock-strategy-comparison.md
- records/experiments/v2-stock-strategy-expansion-rerun.md
- records/experiments/v2-stock-failure-injection.md
```

---

## v3.1. Waiting Room + Active Token

### Goal

Control entry traffic before users reach the purchase and stock decision path.

### Architecture Level

```text
modular monolith feature module: waitingroom
shared PostgreSQL / Redis topology
```

### Allowed

```text
Redis ZSET waiting queue
active token with TTL
active token purchase guard
admission scheduler
fixed batch admission comparison
hybrid admission comparison
direct access comparison
waiting room metrics
waiting status API
```

### Forbidden

```text
reservation table
reservation TTL
idempotency key
Redis stock compensation
payment queue
payment worker
RabbitMQ
reward allocation
duplicate webhook handling
DLQ
outbox pattern
reconciliation worker
MSA-style service split
```

### Success Metrics

```text
request without active token is rejected
duplicate waiting room entry does not create duplicate queue members
entry traffic is controlled before stock decision
waiting queue size is observable
active token issued count is observable
active token rejected count is observable
direct access, fixed batch admission, and hybrid admission are compared
purchase path attempt count is lower than direct access under the same burst shape
oversell_count remains 0 for the selected stock strategy
```

### Result

```text
to be filled after v3.1 experiment
```

---

## v3.2. Reservation + Idempotency + Compensation

### Goal

Make the Redis Lua stock decision path recoverable when DB reservation persistence fails after Redis deduction.

### Architecture Level

```text
modular monolith feature module: reservation
Redis Lua stock decision remains the main path
PostgreSQL remains the durable business truth
```

### Allowed

```text
reservation table
reservation status
short-term idempotency key
one active reservation per user/product
reservation TTL marker in Redis
immediate Redis stock compensation after DB reservation save failure
failure injection after Redis stock decision
duplicate request comparison
reservation and compensation metrics
```

### Forbidden

```text
duplicate webhook handling
delayed webhook handling
RabbitMQ
payment worker
Mock PG
DLQ
outbox pattern
reconciliation worker
Kafka
MSA-style service split
```

### Success Metrics

```text
Redis stock decision count and DB reservation count stay aligned under normal load
failure injection after Redis deduction is compensated or recorded as a recoverable gap
duplicate purchase retry does not create duplicate active reservations
idempotency hit count is observable
compensation success/failure count is observable
rdb-atomic remains available as the control baseline
```

### Result

```text
to be filled after v3.2 experiment
```

---

## v3.3. RabbitMQ + Payment Worker

### Goal

Separate external payment delay from the API request thread.

### Architecture Level

```text
modular monolith feature module: payment
RabbitMQ + Payment Worker main path
Mock PG integration
```

### Allowed

```text
RabbitMQ
Payment Worker
Mock PG
success scenario
fail scenario
delay scenario
timeout scenario
simple retry
payment queue metrics
```

### Forbidden

```text
duplicate webhook handling
delayed webhook handling
DLQ
outbox pattern
reconciliation worker
Kafka
MSA-style service split
```

### Success Metrics

```text
API response time is not directly tied to Mock PG delay
payment queue backlog is observable
worker throughput is observable
payment success/failure/timeout count is observable
PG timeout becomes UNKNOWN or retry target
```

### Result

```text
to be filled after v3.3 experiment
```

---

## v4. Reward Allocation Policy

### Goal

Compare reward allocation policies after payment success.

### Architecture Level

```text
modular monolith feature module: reward
minimal policy implementation
```

### Allowed

```text
payment success processing order based reward allocation
PG approved_at based comparison
reward allocation persistence
reward policy tests
```

### Forbidden

```text
campaign management
coupon system
real settlement
complex reward ranking
```

### Success Metrics

```text
reward allocation count <= reward stock
reward allocation policy is documented
trade-off between processing order and approved_at is explained
```

### Result

```text
to be filled after v4 implementation
```

---

## v5+. Advanced Scope

Advanced scope is recorded in `docs/05-workflow-future-scope.md`.
