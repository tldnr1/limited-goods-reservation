# v3.2 Front Gate Implementation Plan

## Purpose

This plan preserves the next implementation direction after the v3.2 baseline load result and ADR 0002.

The goal is not to prove that Redis Lua is always faster. The goal is to compare:

```text
Redis as stock-decision-only component
Redis as minimal front gate
RDB atomic as DB-truth control baseline
```

under scenarios that separate direct burst, waiting-room-admitted flow, bypass requests, duplicate/idempotent retry, and DB persistence failure.

## Starting Point

Current branch:

```text
feature/v3-2-frontgate
```

Current main baseline:

```text
PR #7 merged
records/experiments/v3-2-reservation-load-baseline.md
```

Baseline observation:

```text
current v3.2 consistency passed: gap 0, oversell 0, unexpected 0
current Redis Lua normal path was slower than RDB atomic in the 1000-user baseline
current flow checks DB idempotency and duplicate reservations before the active-token guard
```

## Implementation Scope

Implement a minimal Redis front gate.

The intended Redis Lua gate should atomically decide:

```text
active token exists
idempotency key is not already PROCESSING
user/product reservation marker is not already PROCESSING
stock is available
```

If accepted, Redis should atomically:

```text
consume active token
decrement stock
set idempotency PROCESSING marker with TTL
set user/product PROCESSING marker with TTL
```

Then the service should:

```text
save PostgreSQL reservation
finalize Redis markers on success
compensate Redis stock and markers on DB persistence failure
return retryable failure when compensation succeeds after injected failure
```

## Out of Scope

Do not implement these in the next pass:

```text
observer / reconciliation worker
outbox pattern
RabbitMQ / payment worker
real PG/payment integration
Kubernetes / multi-API deployment
Redis cluster
expanded commerce schema with SKU, shipping, or users table
```

## Likely Code Areas

Read and change only the necessary parts:

```text
src/main/java/com/limitedgoodsreservation/purchase/service/PurchaseService.java
src/main/java/com/limitedgoodsreservation/stock/strategy/RedisLuaStockStrategy.java
src/main/java/com/limitedgoodsreservation/stock/strategy/StockCompensationService.java
src/main/java/com/limitedgoodsreservation/waitingroom/service/WaitingRoomService.java
src/main/java/com/limitedgoodsreservation/waitingroom/service/RedisWaitingRoomStore.java
src/main/java/com/limitedgoodsreservation/reservation/repository/ReservationRepository.java
src/main/java/com/limitedgoodsreservation/reservation/metrics/ReservationMetrics.java
src/test/java/com/limitedgoodsreservation/purchase/service/
src/test/java/com/limitedgoodsreservation/stock/strategy/
k6/v3-2/reservation-consistency.js
scripts/v3-2/run-reservation-load-matrix.ps1
```

## Design Checks Before Coding

Decide the minimal Redis keys and TTLs before editing code.

Candidate keys:

```text
waiting:active:{productId}:{userId}
stock:available:{productId}
reservation:idem:{idempotencyKey}
reservation:user:{productId}:{userId}
```

Candidate marker values:

```text
PROCESSING
RESERVED:{reservationId}
FAILED_RETRYABLE
```

Candidate TTL policy:

```text
PROCESSING markers: short TTL, long enough for DB save under load
RESERVED markers: longer TTL or no TTL for the experiment
FAILED_RETRYABLE markers: short TTL
```

Be explicit that TTL does not replace reconciliation. It only bounds stale Redis markers in this v3.2 experiment.

## Test Plan

Unit or focused service tests:

```text
missing active token rejects before stock decision
front gate accepts one candidate and decrements Redis stock
same idempotency key does not create duplicate reservation work
same user/product does not create duplicate reservation work
DB save failure compensates stock and markers
RDB atomic control still works
```

Smoke test:

```text
normal reservation succeeds
duplicate idempotency request is reused or rejected according to the chosen minimal policy
active-token bypass is rejected
injected failure returns RESERVATION_FAILED_RETRYABLE
Redis stock compensation restores stock
gap remains 0
```

Load scenarios:

```text
direct burst with waiting room disabled
waiting-room-admitted flow
active-token bypass flow
failure injection flow
duplicate/idempotency flow
```

Comparison rows:

```text
redis-lua current/baseline if retained
redis-frontgate
rdb-atomic
```

Minimum success conditions:

```text
oversell_count = 0
decision_reservation_gap = 0
unexpected_responses = 0
duplicate_reservation_count = 0
```

Comparison metrics:

```text
p95 / p99 HTTP latency
p95 / p99 reservation request latency
stock decision count
reservation attempt/success/idempotency hit/duplicate rejected count
active-token rejection count
compensation success/failure count
DB reserved count
Redis available stock
```

## Portfolio Interpretation Target

The expected portfolio conclusion should be conditional:

```text
Redis Lua was strong in v2 direct burst because it avoided DB hot-row stock updates for sold-out requests.
After v3.2 idempotency and DB reservation truth were added, stock-decision-only Redis no longer clearly won.
The next pass checks whether moving Redis forward as a minimal front gate restores DB-protection value.
RDB atomic remains the simpler control baseline and may be preferable when waiting-room admission already keeps DB load within a safe range.
```

