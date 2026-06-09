# 03. Architecture

This document is the Truth for package structure, dependency direction, and architecture evolution by version.

Version scope belongs to `docs/01-roadmap.md`. ERD and identifiers belong to `docs/02-domain-data.md`.

---

## 1. Architecture Principle

Architecture should appear because the version objective needs it.

```text
v0: runnable skeleton only
v1: feature-based N-tier baseline
v2: focused port/adapter around stock strategy comparison
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

## 4. v2 Focused Port/Adapter

Purpose:

```text
compare stock consistency strategies without rewriting every feature
```

Package direction:

```text
src/main/java/com/limitedgoodsreservation/
  purchase/
    adapter/in/web/
    application/
  stock/
    domain/
    application/
      port/
    adapter/out/persistence/
    adapter/out/redis/
  order/
    domain/
    application/
    adapter/out/persistence/
  global/
```

Rules:

```text
introduce ports around stock consistency only when comparison needs them
keep strategy interfaces small
keep each strategy measurable with the same scenario shape
do not rewrite the whole project into strict DDD
```

Foundation default:

```text
feature/v2 starts with a naive-rdb stock deduction adapter.
This adapter preserves the v1 read-check-write failure so later experiment branches can compare against the same baseline.
Redis infrastructure may exist before Redis stock logic is selected.
```

Possible strategy adapters:

```text
RDB atomic update
RDB pessimistic lock
RDB optimistic lock
Redis distributed lock
Redis Lua
```

---

## 5. v3+ Modular Monolith

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
  payment/
  reward/
  global/
```

Rules:

```text
waitingroom appears when entry traffic control is implemented
payment appears when PG delay isolation is implemented
reward appears when reward allocation is implemented
reconciliation stays future scope until explicitly introduced
do not split into separately deployed services
```

---

## 6. Runtime Components

Runtime topology should remain reproducible through Docker Compose.

```text
v0: Spring API container + PostgreSQL + k6 smoke container
v1: Spring API container + PostgreSQL + k6 oversell scenario
v2: Spring API container + PostgreSQL + Redis when Redis strategies are tested
v3.1: Spring API container + PostgreSQL + Redis + waiting room / active token
v3.2: Spring API container + PostgreSQL + Redis + RabbitMQ + Payment Worker + Mock PG
```

Local Java execution is allowed for fast feedback, but version-level verification should use Docker Compose.

---

## 7. Data Responsibility Split

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
- reservation TTL
- waiting queue
- active token
- short-term idempotency when introduced

RabbitMQ:
- asynchronous payment job delivery
- payment delay isolation
- worker-based retry
```

Do not make Redis the only durable source of business truth.
