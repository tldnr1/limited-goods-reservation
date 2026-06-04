# 05. Testing Strategy

This document defines how tests should support the project narrative.

The goal is not only high coverage. The goal is to prove each version's problem and improvement.

---

## 1. Testing Principle

```text
Core scenarios must be defined by the project owner.
Codex may implement the tests after the scenario and expected result are clear.
```

Each important test should answer:

```text
What failure or behavior does this prove?
Which version does it belong to?
What metric or assertion shows success?
```

---

## 2. Test Stack

```text
JUnit5
AssertJ
Spring Boot Test
Testcontainers
k6
```

Version-level verification should be runnable through Docker Compose. Local IDE or local Java execution may be used for fast feedback, but Docker Compose should remain the reproducible path for Codex, CI, and load-test documentation.

Suggested Testcontainers:

```text
PostgreSQL
Redis
RabbitMQ
```

---

## 3. Test Types

### Unit Test

Purpose:

```text
state transition
policy logic
pure domain behavior
```

Examples:

```text
Reservation status transition
Order status transition
Payment timeout classification
Reward allocation policy
```

---

### Application Use Case Test

Purpose:

```text
business flow behavior
transaction boundary
port interaction
```

Examples:

```text
ReserveProductUseCase
IssueActiveTokenUseCase
RequestPaymentUseCase
ProcessPaymentResultUseCase
```

---

### Integration Test

Purpose:

```text
verify integration with PostgreSQL, Redis, RabbitMQ
```

Examples:

```text
Redis Lua reservation atomicity
reservation TTL behavior
RabbitMQ payment job publish/consume
JPA persistence behavior
```

---

### k6 Load Test

Purpose:

```text
reproduce failures and compare version-level improvements
```

Examples:

```text
v1 oversell baseline
v2.2 oversell=0
v2.4 two API instances consistency
v3.1 waiting room traffic control
v3.2 payment delay isolation
```

---

## 4. High-Priority Scenarios

### v1 Oversell Reproduction

Given:

```text
initial stock = 100
concurrent requests > 100
naive purchase flow
```

Expected:

```text
successful order count > 100
oversell_count > 0
```

---

### v2.2 Oversell=0 Validation

Given:

```text
initial stock = 100
concurrent requests = 500 / 1000 / 3000
Redis Lua reservation
```

Expected:

```text
oversell_count = 0
sold + reserved <= initial stock
```

---

### v2.3 Idempotency

Given:

```text
same idempotency key is submitted multiple times
```

Expected:

```text
same result is returned
duplicate_order_count = 0
```

---

### v2.3 One User One Product

Given:

```text
same userId and productId attempts multiple active reservations
```

Expected:

```text
first active reservation succeeds
next attempt returns 409 Already Reserved
```

Given:

```text
same userId and productId already has paid order
```

Expected:

```text
next purchase attempt returns 409 Already Purchased
```

---

### Reservation TTL Expiration

Given:

```text
reservation TTL is short in test profile
reservation is not paid until TTL expires
```

Expected:

```text
reservation becomes EXPIRED or RELEASED
stock becomes available again
```

---

### v3.1 Active Token Required

Given:

```text
user has no active token
```

Expected:

```text
reserve request is rejected
403 or 409 response
```

Given:

```text
user has active token
```

Expected:

```text
reserve request can proceed to Redis Lua reservation
```

---

### v3.2 Payment Timeout

Given:

```text
Mock PG scenario = timeout
```

Expected:

```text
payment is not treated as confirmed failure
payment becomes UNKNOWN or retry target
```

---

### v3.2 Worker Retry

Given:

```text
payment processing fails or times out
retry count < max retry
```

Expected:

```text
worker retries payment job
retry count increases
```

---

## 5. Tests Codex Can Generate

Codex may generate boilerplate tests for:

```text
DTO validation
repository CRUD
exception response format
invalid productId
invalid userId
invalid status transition edge cases
controller test skeletons
service test skeletons
```

But Codex should not invent new core scenarios without updating docs or asking for confirmation.

---

## 6. Test Naming Convention

Prefer names that describe behavior.

Example:

```text
should_reproduce_oversell_when_naive_purchase_receives_concurrent_requests
should_prevent_oversell_when_redis_lua_reservation_is_used
should_reject_reservation_when_active_token_is_missing
should_mark_payment_unknown_when_pg_timeout_occurs
```

---

## 7. Test Profiles

Suggested profiles:

```text
test
load-test
local
```

Test profile may override:

```text
reservation TTL
active token TTL
Mock PG scenario
retry delay
```
