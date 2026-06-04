# 01. Version Roadmap

This document defines the version boundaries of the project.

The most important rule is:

```text
Do not implement a later-version solution in an earlier version.
```

Each version should answer:

```text
What problem is being observed?
What alternatives are being considered?
What is the selected main path?
What metric proves the improvement?
What is intentionally excluded?
```

---

## v0. Documentation / Project Baseline

### Purpose

Prepare project documents and working rules before implementation.

### Scope

```text
AGENTS.md
project charter
version roadmap
domain model
architecture rules
data model
experiment policy
testing strategy
load test and observability plan
GitHub workflow
future scope
Spring Boot skeleton
initial package structure
Docker-first local experiment structure
API Dockerfile
API Docker Compose service
PostgreSQL Docker Compose service
k6 Docker Compose service skeleton
basic Actuator / Micrometer setup
```

### Success Criteria

```text
Codex can understand the project direction without guessing.
Version boundaries are explicit.
Main path and experiment path are separated.
Spring Boot application can be bootstrapped as a Docker Compose service without business features.
Local experiment topology is reproducible with Docker Compose.
Local Java installation is optional for developer convenience, not required for official verification.
```

---

## v1. Naive Purchase Baseline

### Purpose

Reproduce oversell with a naive purchase flow.

### Problem

A naive RDB read-check-write flow can create more successful orders than available stock under high concurrency.

### Main Flow

```text
POST /orders/purchase

1. Read product stock from RDB.
2. Check stock > 0.
3. Decrease stock.
4. Create order.
```

### Expected Failure

```text
initial stock = 100
concurrent users = about 1000
created successful orders > 100
oversell_count > 0
```

### Allowed

```text
Spring Boot API
PostgreSQL
JPA
simple Product
simple ProductStock
simple Order
k6 baseline test
```

### Excluded

```text
Redis
Lua Script
Reservation
Idempotency
Waiting Room
Active Token
RabbitMQ
Payment Worker
Reward allocation
```

---

## v2.1. Inventory Consistency Alternatives

### Purpose

Compare inventory consistency alternatives before selecting the main path.

### Alternatives

```text
RDB atomic update
RDB pessimistic lock
RDB optimistic lock
Redis distributed lock
Redis Lua
```

### Comparison Criteria

```text
oversell prevention
behavior under API scale-out
latency under high concurrency
implementation complexity
failure and recovery complexity
fit for short check-and-reserve operation
portfolio explainability
```

### Main Path Decision

The main path is expected to be Redis Lua because the target operation is:

```text
single product stock
short check-and-reserve operation
high concurrency
need for atomicity
simple real-time control state
```

### Implementation Policy

Not all alternatives must be implemented fully.

Acceptable outputs:

```text
experiment branch
minimal benchmark
conceptual comparison document
metric table
ADR-style decision record
```

### Example Branches

```text
experiment/v2-1-rdb-atomic-update
experiment/v2-1-rdb-pessimistic-lock
experiment/v2-1-redis-distributed-lock
docs/experiments/v2-1-inventory-consistency.md
```

---

## v2.2. Redis Lua Reservation

### Purpose

Implement Redis Lua reservation as the main path and achieve oversell=0.

### Main Flow

```text
POST /reservations

1. Receive userId and productId.
2. Run Redis Lua reservation script.
3. If reservation succeeds, create Reservation row.
4. Create Order with PENDING_PAYMENT status.
5. Reservation is held for TTL.
```

### Success Criteria

```text
initial stock = 500
concurrent users = about 10000
oversell_count = 0
sold + reserved <= initial stock
```

### Allowed

```text
Redis
Redis Lua Script
ReservationStatus
Order PENDING_PAYMENT
reservation TTL
best-effort Redis compensation on RDB failure
```

### Excluded

```text
Waiting Room
Active Token
RabbitMQ
Payment Worker
Reward allocation
```

### Example Branch

```text
feature/v2-2-redis-lua-reservation
```

---

## v2.3. Idempotency + One User One Product

### Purpose

Prevent duplicate requests and enforce one active reservation/order per user-product pair.

### Rules

```text
same idempotency key:
return previous result

different idempotency key but same active reservation:
409 Already Reserved

already paid order exists:
409 Already Purchased
```

### Success Criteria

```text
duplicate_order_count = 0
one active reservation per userId + productId
one paid order per userId + productId
```

### Example Branch

```text
feature/v2-3-idempotency-one-user-one-product
```

---

## v2.4. API Scale-out Consistency

### Purpose

Verify that inventory consistency is maintained when API servers are scaled out.

### Structure

```text
Nginx
→ Spring API instance 1
→ Spring API instance 2
→ Redis
→ PostgreSQL
```

### Success Criteria

```text
API instances = 2
initial stock = 500
concurrent users = about 10000
oversell_count = 0
sold + reserved <= initial stock
```

### Main Message

Inventory consistency is not dependent on a single API instance's memory. It is centralized in Redis Lua reservation.

### Example Branch

```text
feature/v2-4-api-scaleout-consistency
```

---

## v3.1. Waiting Room + Active Token

### Purpose

Control traffic before users reach the reservation API.

### Alternatives

```text
direct access
rate limit
waiting room + active token
```

### Main Path

```text
Waiting Room + Active Token
```

### Flow

```text
1. User enters waiting queue.
2. Admission Scheduler issues active tokens gradually.
3. User with active token can call reservation API.
4. User without active token is rejected.
```

### Important Rule

```text
Active token is not a purchase guarantee.
Active token is a temporary right to attempt reservation.
```

### Success Criteria

```text
reserve request without active token is rejected
reservation API direct traffic is reduced
waiting queue size is observable
active token issued count is observable
p95 latency is improved or controlled compared to direct access
```

### Example Branches

```text
feature/v3-1-waiting-room-active-token
experiment/v3-1-direct-access
experiment/v3-1-rate-limit
```

---

## v3.2. RabbitMQ + Payment Worker

### Purpose

Separate external payment delay from the API request thread.

### Alternatives

```text
synchronous payment
Spring background task
message queue worker
```

### Main Path

```text
RabbitMQ + Payment Worker
```

### Flow

```text
1. Reservation succeeds.
2. Order PENDING_PAYMENT is created.
3. Payment READY is created.
4. Payment job is published to RabbitMQ.
5. API responds without waiting for slow PG result.
6. Payment Worker consumes job.
7. Worker calls Mock PG.
8. Payment result updates Payment, Order, and Reservation.
```

### Mock PG MVP Scenarios

```text
success
fail
delay
timeout
```

### Timeout Rule

```text
PG timeout is not confirmed failure.
It should become UNKNOWN or retry target.
```

### Retry Rule

```text
max retry = 3
fixed delay or simple backoff
timeout is retry target
DLQ is future scope
```

### Success Criteria

```text
API response time is not directly tied to PG delay
payment queue backlog is observable
worker throughput is observable
payment success/failure/timeout count is observable
```

### Example Branches

```text
feature/v3-2-payment-worker
experiment/v3-2-sync-payment
experiment/v3-2-background-task
```

---

## v4. Reward Allocation Policy

### Purpose

Compare reward allocation policies after payment success.

### Domain Difference

Limited goods:

```text
limited product stock
reservation before payment
```

Reward:

```text
product purchase can succeed
limited reward is allocated after payment success
```

### MVP Policy

```text
Policy A:
payment success processing order based reward allocation
```

### Improvement Candidate

```text
Policy B:
PG approved_at based reward allocation
```

### Success Criteria

```text
reward allocation count <= reward stock
reward allocation policy is documented
trade-off between processing order and approved_at is explained
```

### Example Branch

```text
feature/v4-reward-allocation
```

---

## v5+. Advanced Scope

See:

```text
docs/09-future-scope.md
```
