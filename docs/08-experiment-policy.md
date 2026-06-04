# 08. Experiment Policy

This document defines how comparison experiments should be planned and documented.

---

## 1. Purpose of Experiments

Experiments exist to support architectural decisions.

The goal is not to implement every alternative production-ready.

The goal is:

```text
compare plausible alternatives
measure or reason about trade-offs
select main path
record the decision
```

---

## 2. Main Path vs Experiment Path

### Main Path

```text
feature/*
```

Main path is the selected implementation direction that will be developed and maintained.

### Experiment Path

```text
experiment/*
```

Experiment path is optional comparison work.

Experiment branches:

```text
do not need production-level polish
do not need complete edge-case handling
do not need to be merged into main
may be kept as saved implementation records
must produce a written result if they influence a decision
```

---

## 3. Experiment Documentation Location

Use:

```text
docs/experiments/
```

Examples:

```text
docs/experiments/v2-1-inventory-consistency.md
docs/experiments/v3-1-entry-control.md
docs/experiments/v3-2-payment-processing.md
docs/experiments/v4-reward-allocation.md
```

---

## 4. Standard Experiment Document Format

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

---

## 5. v2.1 Inventory Consistency Comparison

Question:

```text
How should a short check-and-reserve operation for single product stock be made safe under high concurrency?
```

Alternatives:

```text
RDB atomic update
RDB pessimistic lock
RDB optimistic lock
Redis distributed lock
Redis Lua
```

Comparison criteria:

```text
oversell prevention
latency under high concurrency
API scale-out behavior
implementation complexity
failure recovery complexity
fit for short atomic operation
portfolio explainability
```

Expected main path:

```text
Redis Lua
```

Reason:

```text
The operation is short, product-level, high-concurrency, and needs atomic check-and-reserve behavior.
```

---

## 6. v3.1 Entry Control Comparison

Question:

```text
How should traffic be controlled before users reach the reservation API?
```

Alternatives:

```text
direct access
rate limit
waiting room + active token
```

Comparison criteria:

```text
ability to absorb traffic surge
server protection
user experience
implementation complexity
separation from reservation consistency
metric visibility
```

Expected main path:

```text
Waiting Room + Active Token
```

Reason:

```text
Waiting Room controls admission before reservation and keeps the meaning of active token separate from stock reservation.
```

---

## 7. v3.2 Payment Processing Comparison

Question:

```text
How should external PG delay be separated from API request processing?
```

Alternatives:

```text
synchronous payment
Spring background task
RabbitMQ + Payment Worker
```

Comparison criteria:

```text
API thread blocking
retry capability
failure handling
worker scalability
observability
implementation complexity
```

Expected main path:

```text
RabbitMQ + Payment Worker
```

Reason:

```text
Message queue based worker processing makes payment delay observable and separates it from the API request lifecycle.
```

---

## 8. v4 Reward Allocation Comparison

Question:

```text
What ordering rule should be used for limited reward allocation after payment success?
```

Alternatives:

```text
payment success processing order
PG approved_at order
random allocation among paid users
```

MVP main path:

```text
payment success processing order
```

Improvement candidate:

```text
PG approved_at order
```

Reason:

```text
Processing order is simple enough for MVP and shows the difference between Limited stock reservation and post-payment Reward allocation.
```
