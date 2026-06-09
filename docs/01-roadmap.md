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
v2: stock strategy comparison with focused port/adapter
v3.1: waiting room + active token
v3.2: RabbitMQ + payment worker
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
to be filled after v1 experiment
```

---

## v2. Stock Strategy Comparison

### Goal

Compare stock consistency strategies and select the main path for preventing oversell.

### Architecture Level

```text
focused port/adapter around stock consistency
do not rewrite every feature into strict DDD
```

### Allowed

```text
RDB atomic update
RDB pessimistic lock
RDB optimistic lock
Redis distributed lock
Redis Lua
minimal experiment branches
stock strategy ports where comparison needs them
Redis when Redis strategies are tested
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
selected main path is documented
selected main path achieves oversell_count = 0
sold + reserved <= initial stock when reservation exists
```

### Result

```text
to be filled after v2 comparison
```

---

## v3.1. Waiting Room + Active Token

### Goal

Control entry traffic before users reach the reservation attempt.

### Architecture Level

```text
modular monolith feature module: waitingroom
shared PostgreSQL / Redis topology
```

### Allowed

```text
waiting queue
active token
admission scheduler
direct access comparison
rate limit comparison
waiting room metrics
```

### Forbidden

```text
payment worker
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
entry traffic is controlled before stock reservation
waiting queue size is observable
active token issued count is observable
```

### Result

```text
to be filled after v3.1 experiment
```

---

## v3.2. RabbitMQ + Payment Worker

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
```

### Result

```text
to be filled after v3.2 experiment
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
