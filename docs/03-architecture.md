# 03. Architecture

This document is the Truth for package structure, dependency direction, and architecture evolution by version.

Version scope belongs to `docs/01-roadmap.md`. ERD and identifiers belong to `docs/02-domain-data.md`.

---

## 1. Architecture Principle

Architecture should appear because the version objective needs it.

```text
v0: runnable skeleton only
v1: feature-based N-tier baseline
v2: v1-like layered structure with stock strategy comparison
v3+: modular monolith with feature-based modules
```

Do not introduce a pattern just because it is the final target.

---

## 2. v0 Skeleton

Expected structure:

```text
src/main/java/com/limitedgoodsreservation/
  LimitedGoodsReservationApplication.java
```

Rules:

```text
no business package placeholders
no empty future package-info.java files
no purchase/reservation/payment/reward code
```

---

## 3. v1 Feature-Based N-Tier

Purpose:

```text
make the naive purchase failure easy to see and explain
```

Package direction:

```text
src/main/java/com/limitedgoodsreservation/
  purchase/
    controller/
    dto/
    service/
  product/
    entity/
    repository/
  order/
    entity/
    repository/
  global/
```

Dependency direction:

```text
controller -> service -> repository -> database
```

Rules:

```text
JPA entities may live directly in feature packages
service may coordinate the naive purchase flow directly
no port/adapter abstraction
no stock strategy abstraction
```

---

## 4. v2 Layered Stock Strategy

Purpose:

```text
compare stock consistency strategies while keeping the v1 code shape easy to explain
```

Package direction:

```text
src/main/java/com/limitedgoodsreservation/
  purchase/
    controller/
    dto/
    service/
    metrics/
  product/
    entity/
    repository/
  order/
    entity/
    repository/
  stock/
    strategy/
  global/
```

Rules:

```text
keep controller -> service -> repository readable like v1
isolate only stock deduction behind a small strategy interface
keep each strategy measurable with the same scenario shape
do not use full hexagonal/strict DDD in v2
reconsider port/adapter in v3 when waiting room, active token, payment, and worker boundaries appear
```

Foundation default:

```text
feature/v2 starts with a naive-rdb stock strategy.
This strategy preserves the v1 read-check-write failure so later strategies can compare against the same baseline.
```

Official comparison strategies:

```text
naive-rdb
RDB atomic update
RDB pessimistic lock
Redis Lua
```

Redis Lua rule:

```text
Redis stock key stock:available:{productId} is the stock decision source of truth for redis-lua.
The v2 API still saves the order synchronously, so measure stock decision latency separately from HTTP latency.
DB failure compensation after Redis deduction is future scope.
```

---

## 5. v3.1 Entry Control Module

Purpose:

```text
limit how many users can enter the existing purchase and stock decision path
```

Package direction:

```text
src/main/java/com/limitedgoodsreservation/
  waitingroom/
    controller/
    dto/
    service/
    scheduler/
    metrics/
```

Rules:

```text
keep the existing purchase flow readable
place waiting queue, active token, and admission policy in waitingroom
validate active token explicitly before stock deduction
do not introduce reservation, payment, or worker abstractions in v3.1
```

Default policy:

```text
queue structure: Redis ZSET
queue score: Redis INCR sequence
active token TTL: 60 seconds
active token missing response: 409 conflict
admission policy: hybrid batch/capacity
admission interval: 1 second
batchSize: 20
activeCapacity: 100
```

---

## 6. v3+ Modular Monolith

Purpose:

```text
add feature modules for new limited-sale failure modes
```

Target direction:

```text
src/main/java/com/limitedgoodsreservation/
  purchase/
  stock/
  waitingroom/
  reservation/
  payment/
  reward/
  global/
```

Rules:

```text
waitingroom appears when entry traffic control is implemented
reservation appears when Redis stock decisions become durable reservations
payment appears when PG delay isolation is implemented
reward appears when reward allocation is implemented
outbox and reconciliation workers stay future scope until explicitly introduced
do not split into separately deployed services
```

---

## 7. Runtime Components

Runtime topology should remain reproducible through Docker Compose.

```text
v0: Spring API container + PostgreSQL + k6 smoke container
v1: Spring API container + PostgreSQL + k6 oversell scenario
v2: Spring API container + PostgreSQL + Redis when Redis strategies are tested
v3.1: Spring API container + PostgreSQL + Redis + waiting room / active token
v3.2: Spring API container + PostgreSQL + Redis + reservation / idempotency / compensation
v3.3: Spring API container + PostgreSQL + Redis + RabbitMQ + Payment Worker + Mock PG
```

Local Java execution is allowed for fast feedback, but version-level verification should use Docker Compose.

---

## 8. Data Responsibility Split

```text
PostgreSQL:
- durable business records
- products
- product_stock
- orders
- reservations/payments/reward_allocations when introduced

Redis:
- real-time high-concurrency control
- stock reservation decision when selected
- waiting queue
- active token
- reservation TTL when introduced
- short-term idempotency when introduced

RabbitMQ:
- asynchronous payment job delivery
- payment delay isolation
- worker-based retry
```

Do not make Redis the only durable source of business truth.

---

## 9. v3.2 Reservation Consistency Module

Purpose:

```text
make the Redis Lua stock decision recoverable when PostgreSQL reservation persistence fails
```

Package direction:

```text
src/main/java/com/limitedgoodsreservation/
  reservation/
    entity/
    exception/
    metrics/
    repository/
```

Request order:

```text
idempotency check
-> existing user/product reservation check
-> active token consume
-> stock decision
-> reservation insert
-> compensation and active-token restore on persistence failure
```

Rules:

```text
PostgreSQL reservations are the v3.2 durable truth.
Redis stock remains the fast decision source for redis-lua.
Redis stock compensation runs only after a successful Redis decision followed by reservation persistence failure.
Active token restore runs only when the persistence failure was handled as retryable.
```
