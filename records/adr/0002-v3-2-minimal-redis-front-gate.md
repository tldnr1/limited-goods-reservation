# ADR 0002. Use a Minimal Redis Front Gate for v3.2

## Status

Accepted for the next v3.2 implementation pass

## Context

v2 selected Redis Lua as the v3-oriented stock decision path because it handled high-VU direct burst load with lower HTTP tail latency and fewer unexpected responses than the RDB atomic control baseline. The v2 purchase flow was intentionally simple:

```text
stock decision
-> order save
```

In v3.2, reservation, idempotency, and compensation were added to close the Redis stock decision to PostgreSQL truth gap. The first v3.2 reservation load baseline recorded the consistency improvement:

```text
decision_reservation_gap = 0
oversell_count = 0
unexpected_responses = 0
```

However, the normal-load baseline did not show Redis Lua outperforming RDB atomic:

```text
redis-lua normal 1000 users: p95 2487.89 ms, p99 2745.12 ms
rdb-atomic normal 1000 users: p95 1659.16 ms, p99 1803.32 ms
```

The current v3.2 purchase flow explains why this can happen:

```text
DB idempotency lookup
-> DB duplicate user/product lookup
-> active token guard
-> Redis Lua or RDB atomic stock decision
-> DB reservation save
```

With this order, Redis Lua no longer acts as a true DB front gate. New purchase requests can reach DB idempotency and duplicate checks before active-token validation, and Redis only handles the stock decision after those DB reads have already happened.

The next design question is:

```text
After v3.1 waiting-room entry control, does Redis Lua still need to be the stock-decision main path?
If Redis is used, should it remain a stock-decision component or move forward as a front gate
that checks active token, idempotency, duplicate user/product state, and stock before DB writes?
```

## Decision

Implement a minimal Redis front gate for the next v3.2 pass.

The minimal front gate should move the first-pass admission decision into Redis before DB reservation work:

```text
validate request
-> Redis front gate checks active token, idempotency processing marker, user/product processing marker, and stock
-> Redis front gate consumes token and decrements stock for an accepted candidate
-> DB reservation save
-> Redis finalize or synchronous compensation
```

The front gate should be minimal, not a full production workflow engine.

Allowed in this pass:

```text
active token guard before DB lookup
Redis Lua atomic gate for active token, stock, idempotency processing marker, and user/product processing marker
synchronous Redis compensation when DB reservation save fails
Redis finalize marker after DB reservation save succeeds
RDB atomic control baseline remains available
direct burst, waiting-room-admitted, bypass, duplicate, and failure scenarios remain separated in tests
```

Deferred from this pass:

```text
observer / reconciliation worker
outbox pattern
payment worker
real payment provider
multi-API deployment
Redis cluster or Sentinel
Kubernetes
full commerce schema expansion
```

## Alternatives

### Keep Current v3.2 Flow

Keep DB idempotency and duplicate checks before the active token and Redis stock decision.

This preserves the current implementation and gives friendly idempotent retry behavior, but it weakens the waiting-room and Redis DB-protection story because bypass or broken-client requests can still touch DB before being rejected.

### Active Token Guard Only

Move active-token validation before DB idempotency and duplicate checks, but keep idempotency and duplicate reservation checks in PostgreSQL.

This is the smallest useful improvement. It protects DB from requests without an active token, but Redis still does not decide duplicate/idempotency state before DB reads for admitted requests.

### Full Redis Front Gate with Reconciliation

Move active token, idempotency, duplicate user/product state, stock decision, pending state, confirmation, and recovery into a Redis-first workflow backed by a reconciliation worker.

This is closest to an operationally robust Redis-front architecture, but it adds a state machine, TTL recovery, unknown-state handling, and periodic repair. That scope is too large for the next portfolio-focused v3.2 pass.

### RDB Atomic as the Main Path

Use v3.1 waiting-room entry control to limit downstream traffic, then keep stock, idempotency, reservation, and duplicate constraints in PostgreSQL.

This may be simpler and more correct for many real services. It remains the control baseline and may become the preferred conclusion if the minimal Redis front gate does not justify its complexity.

## Consequences

The next implementation pass can test whether Redis is more valuable as a true front gate than as a stock-decision-only component.

The design makes the bypass scenario more meaningful:

```text
active token missing
-> reject before DB reservation reads/writes
```

It also makes the Redis Lua path more complex:

```text
Redis state can temporarily diverge from PostgreSQL truth
PROCESSING markers need TTLs
DB save success must be finalized in Redis
DB save failure must compensate Redis stock and markers
server crash between Redis gate success and DB save/finalize remains a known limitation
```

The project should be careful not to claim production-grade exactly-once behavior. The v3.2 goal is to build and measure a minimal front-gate testbed, then compare it with the simpler RDB atomic control baseline.

## Evidence

Related records:

```text
records/experiments/v2-stock-strategy-comparison.md
records/experiments/v2-stock-strategy-expansion-rerun.md
records/experiments/v2-stock-failure-injection.md
records/experiments/v3-1-entry-control.md
records/experiments/v3-2-reservation-load-baseline.md
```

