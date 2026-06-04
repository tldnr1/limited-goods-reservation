# AGENTS.md

This file defines the non-negotiable rules for Codex and other AI coding agents working on this repository.

The project is currently in **v0 documentation / project skeleton** stage. Do not jump ahead into later-version implementations unless explicitly requested.

---

## 1. Project Identity

Project name:

```text
limited-goods-reservation
```

This is a Spring Boot backend portfolio project for a limited goods sale system.

The project is not a general e-commerce service. It exists to reproduce and improve the core backend failures that happen in limited sales:

```text
oversell
high-concurrency reservation
entry traffic surge
payment delay
reward allocation policy
```

The core narrative is:

```text
failure reproduction
→ root cause analysis
→ alternative comparison
→ structure selection
→ metric-based validation
→ next version improvement
```

---

## 2. Non-Negotiable Principles

- Do not implement features earlier than their assigned version.
- Do not turn this into a full commerce application.
- Keep business logic explicit enough for a junior backend developer to explain line by line.
- Prefer simple, measurable flows over clever abstractions.
- Every major structural decision must be reflected in docs or experiment notes.
- Tests should prove the version objective, not merely increase coverage.
- Experiment branches are allowed to be incomplete compared to main-path branches.
- Experiment results are more important than production-level polish in experiment branches.

---

## 3. Version Boundaries

### v0 — Documentation / Project Baseline

Purpose:

```text
Define the project charter, version roadmap, architecture principles, domain model, test strategy, experiment policy, and GitHub workflow.
```

Do not implement business features in v0 unless scaffolding is explicitly requested.

v0 scaffolding may define the Docker-first local experiment structure, such as Gradle wrapper, Dockerfile, API service, PostgreSQL service, and k6 service skeletons. This is infrastructure scaffolding, not business feature implementation.

---

### v1 — Naive Purchase Baseline

Purpose:

```text
Reproduce oversell with a naive purchase flow.
```

Allowed:

```text
Spring Boot API
PostgreSQL
JPA
simple product stock
simple order creation
k6 baseline scenario
```

Forbidden in v1:

```text
Redis
Lua script
reservation model
idempotency
1-user-1-product restriction beyond minimal test setup
waiting room
active token
RabbitMQ
Payment Worker
Reward allocation
```

---

### v2.1 — Inventory Consistency Alternatives

Purpose:

```text
Compare inventory consistency alternatives before selecting the main path.
```

Alternatives to compare:

```text
RDB atomic update
RDB pessimistic lock
RDB optimistic lock
Redis distributed lock
Redis Lua
```

This version is mostly documentation and optional experiment branches. Do not force all alternatives into the main branch.

---

### v2.2 — Redis Lua Reservation

Purpose:

```text
Implement Redis Lua based reservation as the main path and achieve oversell=0.
```

Allowed:

```text
Redis
Lua reservation script
ReservationStatus
Order PENDING_PAYMENT
reservation TTL
Redis/RDB responsibility split
```

Forbidden:

```text
waiting room
active token
RabbitMQ
Payment Worker
Reward allocation
```

---

### v2.3 — Idempotency + One User One Product

Purpose:

```text
Prevent duplicate requests and enforce one active reservation/order per user-product pair.
```

Allowed:

```text
idempotency key
userId + productId duplicate protection
Already Reserved response
Already Purchased response
```

---

### v2.4 — API Scale-out Consistency

Purpose:

```text
Verify oversell=0 with Nginx + two Spring API instances.
```

Allowed:

```text
Nginx
multiple API instances
shared Redis
shared PostgreSQL
k6 scale-out scenario
```

---

### v3.1 — Waiting Room + Active Token

Purpose:

```text
Control entry traffic before users reach the reservation API.
```

Alternatives to compare:

```text
direct access
rate limit
waiting room + active token
```

Main path:

```text
Waiting Room + Active Token
```

Important rule:

```text
Active token is not a purchase guarantee. It is a temporary right to attempt reservation.
```

---

### v3.2 — RabbitMQ + Payment Worker

Purpose:

```text
Separate external payment delay from the API request thread.
```

Alternatives to compare:

```text
synchronous payment
Spring background task
message queue worker
```

Main path:

```text
RabbitMQ + Payment Worker
```

Mock PG MVP scenarios:

```text
success
fail
delay
timeout
```

Forbidden in v3.2 MVP:

```text
duplicate webhook
delayed webhook
DLQ
outbox pattern
reconciliation worker
```

---

### v4 — Reward Allocation Policy

Purpose:

```text
Compare reward allocation policies after payment success.
```

MVP main path:

```text
payment success processing order based reward allocation
```

Improvement candidate:

```text
PG approved_at based reward allocation
```

---

### v5+ — Advanced Scope

Candidate future scope:

```text
SKU / size / color option
saleEvent model
multi-quantity order
cart
shipping
real authentication
real PG integration
duplicate webhook
delayed webhook
DLQ
outbox pattern
reconciliation worker
Redis Cluster
Kafka
Kubernetes
AWS deployment
Prometheus / Grafana dashboard
```

---

## 4. Architecture Rules

Use a lightweight Hexagonal Architecture.

Base package structure:

```text
src/main/java/.../
  domain/
  application/
  adapter/
    in/
      web/
    out/
      persistence/
      redis/
      mq/
      pg/
  global/
```

Rules:

- `domain` must not know JPA, Redis, RabbitMQ, HTTP, or Spring Web DTOs.
- `application` coordinates use cases and transaction boundaries.
- `adapter.in.web` contains controllers and request/response DTOs.
- `adapter.out.persistence` contains JPA entities and persistence adapters.
- `adapter.out.redis` contains Redis scripts, reservation TTL, waiting queue, and active token implementations.
- `adapter.out.mq` contains RabbitMQ producer/consumer code.
- `adapter.out.pg` contains Mock PG client code.
- `global` contains config, exception handling, common response, and logging support.

See:

```text
docs/03-architecture.md
```

---

## 5. Domain Simplification Rules

Until v3.2, keep the domain intentionally simple:

```text
product-level single stock
one user can buy one unit of one product
productId-based waiting queue
productId-based active token
productId-based reservation
no SKU
no cart
no multi-quantity order
no shipping
no coupon
no real JWT authentication
```

Use simplified test identity:

```text
X-USER-ID: {userId}
```

Authentication is intentionally simplified because this project focuses on concurrency, consistency, traffic control, and payment delay isolation.

---

## 6. Data Responsibility Rules

Redis handles high-concurrency real-time control.

PostgreSQL handles durable business records and auditability.

```text
Redis:
- stock availability for reservation
- reservation TTL
- user reservation marker
- short-term idempotency key
- waiting queue
- active token

PostgreSQL:
- users
- products
- product_stock
- orders
- reservations
- payments
- reward_allocations
```

Do not make Redis the only durable source of business truth.

---

## 7. Testing Rules

Core scenarios must be defined by the project owner in natural language before Codex writes test code.

High-priority tests:

```text
v1 oversell reproduction
v2.2 oversell=0 validation
reservation TTL expiration
idempotency behavior
one user one product behavior
active token missing reserve failure
PG timeout → UNKNOWN/retry target
Payment Worker retry
```

Codex may generate boilerplate tests for:

```text
DTO validation
repository CRUD
exception response
status transition edge cases
controller/service test skeletons
```

See:

```text
docs/05-testing-strategy.md
```

---

## 8. Experiment Policy

Experiments are used to support architectural decisions.

Branch policy:

```text
main             stable documented baseline
dev              integration branch before main
feature/*        main path implementation
hotfix/*         urgent correction from main
docs/*           documentation changes
experiment/*     optional comparison implementation
```

Experiment output should be documented under:

```text
docs/experiments/
```

Experiment branches do not have to be merged into main.

Experiment branches do not need the same production-level completeness as main-path feature branches.

See:

```text
docs/08-experiment-policy.md
docs/experiments/README.md
```

---

## 9. Documentation Update Rule

When behavior, scope, architecture, data model, test strategy, or version boundaries change, update the related document.

Use docs as the source of project intent.
