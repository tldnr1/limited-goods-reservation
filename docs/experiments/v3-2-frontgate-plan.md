# v3.2 Redis Front Gate Implementation Spec

## Purpose

This document is the implementation blueprint for the next v3.2 pass.

ADR 0002 accepted the next question:

```text
After v3.1 waiting-room entry control, is RDB atomic enough?
Or is a Redis front gate worth its extra consistency and recovery cost?
```

The previous v3.2 Redis Lua implementation is already recorded as a baseline result, but it is not the final design target. It checked PostgreSQL idempotency and duplicate reservation state before the active-token guard and before Redis stock deduction. That order weakened Redis as a DB-protection layer.

The next implementation should compare only these two current candidates:

```text
redis-frontgate: Redis Lua checks admission, idempotency, duplicate user/product state, and stock before DB reservation work.
rdb-atomic: PostgreSQL remains the truth and handles idempotency, duplicate state, stock update, and reservation save.
```

The old `redis-lua` stock-decision-only path is historical evidence for the design change. It does not need to remain in the v3.2 final comparison matrix.

---

## Current Code Blueprint

Use this section as the code index before editing. Do not create duplicate concepts without checking these files first.

### Purchase Entry

```text
src/main/java/com/limitedgoodsreservation/purchase/controller/PurchaseController.java
```

Current request inputs:

```text
X-USER-ID
X-RUN-ID
X-IDEMPOTENCY-KEY
body.productId
```

```text
src/main/java/com/limitedgoodsreservation/purchase/service/PurchaseService.java
```

Current order to replace:

```text
validate request
-> DB idempotency lookup
-> DB user/product duplicate lookup
-> active token consume
-> stock decision
-> DB reservation save
-> Redis stock compensation only for redis-lua
```

Target order:

```text
validate request
-> selected architecture path
```

The two architecture paths should be explicit enough that a reader can see whether DB is protected before reservation work.

### Existing Reservation Truth

```text
src/main/java/com/limitedgoodsreservation/reservation/entity/Reservation.java
src/main/java/com/limitedgoodsreservation/reservation/repository/ReservationRepository.java
```

Current durable reservation truth:

```text
reservations.id
reservations.user_id
reservations.product_id
reservations.idempotency_key
reservations.status
```

Existing DB constraints:

```text
unique(product_id, user_id)
unique(idempotency_key)
```

Keep these constraints. Redis is a fast gate, not the only truth.

### Existing Waiting Room

```text
src/main/java/com/limitedgoodsreservation/waitingroom/service/WaitingRoomService.java
src/main/java/com/limitedgoodsreservation/waitingroom/service/RedisWaitingRoomStore.java
```

Actual Redis keys already used by v3.1:

```text
waiting:sequence:{productId}
waiting:queue:{productId}
waiting:user:{productId}:{userId}
active-token:{productId}:{userId}
active-token:index:{productId}
```

Important existing behavior:

```text
WaitingRoomService.consumeActiveTokenOrThrow(userId, productId)
```

returns immediately when `waiting-room.enabled=false`.

For v3.2 final comparison:

```text
when waiting-room.enabled=true:
  both redis-frontgate and rdb-atomic must reject missing active tokens before DB reservation work

when waiting-room.enabled=false:
  direct burst scenarios skip active-token validation so stock/reservation behavior can be compared
```

### Existing Stock Strategies

```text
src/main/java/com/limitedgoodsreservation/stock/strategy/RdbAtomicStockStrategy.java
src/main/java/com/limitedgoodsreservation/stock/strategy/RedisLuaStockStrategy.java
src/main/java/com/limitedgoodsreservation/stock/strategy/StockDeductionStrategyResolver.java
```

`RdbAtomicStockStrategy` remains the DB-truth control baseline.

`RedisLuaStockStrategy` is the historical stock-decision-only Redis path. It should not be used as the final v3.2 Redis candidate. The new Redis candidate needs userId, productId, idempotencyKey, and active-token state, so it does not fit the current `StockDeductionStrategy.deduct(productId)` shape.

Recommended implementation rule:

```text
Do not force redis-frontgate into StockDeductionStrategy.
Add a small reservation/front-gate component instead.
Keep or remove the old redis-lua bean based on the smallest clean diff, but exclude it from v3.2 final scripts and result tables.
```

### Existing Configuration Trap

Current configuration:

```text
stock.strategy = ${STOCK_STRATEGY:naive-rdb}
StockDeductionStrategyResolver selects only StockDeductionStrategy beans.
```

Therefore this should not be done:

```text
STOCK_STRATEGY=redis-frontgate
```

unless redis-frontgate is incorrectly forced into `StockDeductionStrategy`.

Recommended v3.2 implementation:

```text
add purchase.architecture = ${PURCHASE_ARCHITECTURE:}
support purchase.architecture values:
  redis-frontgate
  rdb-atomic

when purchase.architecture is blank, keep the existing STOCK_STRATEGY-based legacy flow for old v1/v2/v3.1 scripts.
keep stock.strategy for legacy stock strategy tests and for the internal RDB atomic stock update component.
```

This preserves old v1/v2/v3.1 strategy scripts while letting v3.2 compare architecture paths instead of only stock deduction functions.

---

## Target Architecture Paths

### Candidate A: redis-frontgate

Goal:

```text
Reject requests in Redis before DB reservation reads/writes whenever Redis has enough state to decide.
```

Flow:

```text
validate request
-> Redis Lua front gate
   - return RESERVED idempotency retries before active-token enforcement
   - require active token when waiting room is enabled
   - consume active token when required
   - check idempotency marker
   - check user/product reservation marker
   - check stock
   - decrement stock
   - set PROCESSING markers
-> DB reservation save
-> Redis finalize on success
-> Redis compensation on DB persistence failure
```

Redis keys:

```text
active-token:{productId}:{userId}
active-token:index:{productId}
stock:available:{productId}
reservation:idem:{idempotencyKey}
reservation:user:{productId}:{userId}
```

Marker values:

```text
PROCESSING:{productId}:{userId}
RESERVED:{reservationId}:{productId}:{userId}
```

TTL policy:

```text
PROCESSING markers:
  short TTL, long enough for DB save under local load
  fixed default: 30 seconds

RESERVED markers:
  no TTL for this experiment
  PostgreSQL remains truth if Redis marker is missing
```

Known limitation:

```text
TTL bounds stale Redis state, but it is not reconciliation.
A server crash between Redis ACCEPTED and DB save/finalize can still leave temporary divergence.
This pass intentionally does not implement an observer, outbox, or reconciliation worker.
```

### Candidate B: rdb-atomic

Goal:

```text
Use v3.1 waiting room to limit admitted traffic, then keep correctness in PostgreSQL.
```

Flow:

```text
validate request
-> active token guard before DB work when waiting room is enabled
-> DB idempotency lookup
-> DB user/product duplicate lookup
-> RDB atomic stock update
-> DB reservation save in the same transaction
```

Rationale:

```text
This path is simpler and may be sufficient if waiting-room admission keeps DB load inside a safe range.
It is the control baseline for the portfolio conclusion.
```

---

## Redis Front Gate Result Contract

The front gate should return a small explicit result instead of leaking Lua return codes through the service.

Suggested Java names:

```text
RedisReservationFrontGate
RedisFrontGateResult
RedisFrontGateDecision
```

Suggested decisions:

```text
ACCEPTED
ACTIVE_TOKEN_REQUIRED
IDEMPOTENCY_PROCESSING
IDEMPOTENCY_RESERVED
ALREADY_RESERVED
SOLD_OUT
MISSING_STOCK_KEY
```

Service behavior:

```text
ACCEPTED:
  save DB reservation

ACTIVE_TOKEN_REQUIRED:
  throw ActiveTokenRequiredException, HTTP 409

IDEMPOTENCY_PROCESSING:
  reject as in-flight duplicate, HTTP 409 or retryable 503
  fixed for this pass: HTTP 409 to keep client behavior simple

IDEMPOTENCY_RESERVED:
  read DB by idempotency key and return existing reservation
  this DB lookup happens only for idempotent retry, not the first-pass gate

ALREADY_RESERVED:
  throw AlreadyReservedException, HTTP 409

SOLD_OUT:
  throw SoldOutException, HTTP 409

MISSING_STOCK_KEY:
  throw StockDeductionException(UNEXPECTED_FAILURE)
```

---

## Redis Scripts

Keep scripts small and focused.

### Gate Script

Inputs:

```text
KEYS[1] activeTokenKey
KEYS[2] activeTokenIndexKey
KEYS[3] stockKey
KEYS[4] idempotencyKey
KEYS[5] userReservationKey

ARGV[1] requireActiveToken, "1" or "0"
ARGV[2] activeTokenMember, userId as string
ARGV[3] processingValue
ARGV[4] processingTtlMillis
```

Behavior:

```text
if idempotency marker exists:
  return IDEMPOTENCY_RESERVED for completed retries
  return IDEMPOTENCY_PROCESSING for in-flight duplicates

if requireActiveToken == "1" and active token is missing:
  return ACTIVE_TOKEN_REQUIRED

if user/product marker exists:
  return ALREADY_RESERVED

if stock key missing:
  return MISSING_STOCK_KEY

if stock <= 0:
  return SOLD_OUT

if accepted:
  delete active-token key and remove user from active-token index when active token is required
  decrement stock
  set idempotency marker to PROCESSING with TTL
  set user/product marker to PROCESSING with TTL
  return ACCEPTED
```

### Finalize Script

Inputs:

```text
idempotency marker key
user/product marker key
expected PROCESSING value
RESERVED value
```

Behavior:

```text
only replace markers that still match the expected PROCESSING value
set both markers to RESERVED value
```

### Compensation Script

Inputs:

```text
stock key
idempotency marker key
user/product marker key
expected PROCESSING value
```

Behavior:

```text
only compensate if markers still match the expected PROCESSING value
increment stock once
delete PROCESSING markers
return compensated or not_compensated
```

Active-token restore:

```text
After successful compensation, restore the active token through the existing WaitingRoomService when waiting room is enabled.
This matches the current v3.2 retryable failure behavior and keeps the front-gate scripts smaller.
```

---

## Implementation Scope

Allowed:

```text
add a reservation/front-gate component
add redis-frontgate as the v3.2 Redis candidate name
move active-token guard before DB reservation work for rdb-atomic
update k6 and runner scripts to compare redis-frontgate and rdb-atomic
add metrics needed to explain front-gate decisions and compensation
```

Avoid:

```text
full workflow engine
observer / reconciliation worker
outbox pattern
RabbitMQ / payment worker
real PG/payment integration
Kubernetes / multi-API deployment
Redis cluster
expanded commerce schema with SKU, shipping, users, cart, or delivery
JDBC query-count instrumentation unless needed after the first result
```

Recommended code areas:

```text
src/main/resources/application.yml
docker-compose.yml
src/main/java/com/limitedgoodsreservation/purchase/service/PurchaseService.java
src/main/java/com/limitedgoodsreservation/reservation/
src/main/java/com/limitedgoodsreservation/reservation/metrics/ReservationMetrics.java
src/main/java/com/limitedgoodsreservation/stock/strategy/RdbAtomicStockStrategy.java
src/main/java/com/limitedgoodsreservation/stock/strategy/StockDeductionStrategyResolver.java
src/main/java/com/limitedgoodsreservation/waitingroom/service/WaitingRoomService.java
src/main/java/com/limitedgoodsreservation/waitingroom/service/RedisWaitingRoomStore.java
k6/v3-2/reservation-consistency.js
scripts/v3-2/run-reservation-load-matrix.ps1
```

Prefer a small new package:

```text
src/main/java/com/limitedgoodsreservation/reservation/gate/
```

over expanding `stock/strategy` into reservation, idempotency, and active-token concerns.

Script changes for this implementation pass:

```text
k6/v3-2/reservation-consistency.js uses PURCHASE_ARCHITECTURE as the v3.2 comparison axis.
scripts/v3-2/run-reservation-consistency-smoke.ps1 verifies redis-frontgate and rdb-atomic before load testing.
scripts/v3-2/run-reservation-load-matrix.ps1 reports architecture = redis-frontgate or rdb-atomic.
The scripts may keep a backward-compatible strategy column/tag, but its value represents the v3.2 architecture.
```

---

## Test Design

### Focused Code Tests

These should run before Docker smoke/load checks.

```text
redis-frontgate accepts one request:
  active token exists
  stock exists and is positive
  no idempotency marker
  no user/product marker
  result ACCEPTED
  stock decremented
  active token consumed
  PROCESSING markers written

redis-frontgate rejects missing active token:
  waiting room enabled
  active token missing
  result ACTIVE_TOKEN_REQUIRED
  stock unchanged
  no DB reservation save

redis-frontgate skips active token in direct burst mode:
  waiting room disabled
  active token missing
  stock and marker checks still run

redis-frontgate handles idempotency retry:
  RESERVED idempotency marker exists
  service reads DB by idempotency key
  response is reused
  no stock decrement

redis-frontgate handles in-flight duplicate:
  PROCESSING idempotency marker exists
  request is rejected
  no stock decrement

redis-frontgate handles same user/product duplicate:
  user/product marker exists
  request is rejected
  no stock decrement

redis-frontgate compensates DB save failure:
  gate accepted
  injected failure before DB save
  stock incremented back
  PROCESSING markers removed
  active token restored when waiting room enabled

rdb-atomic protects DB from bypass:
  waiting room enabled
  active token missing
  DB idempotency lookup is not called
  RDB stock update is not called
```

### Smoke Scenarios

Use Docker Compose for version-level checks.

```text
normal redis-frontgate:
  expected created = stock
  gap = 0
  oversell = 0
  unexpected = 0

duplicate redis-frontgate:
  same idempotency key repeated
  duplicate reservations = 0
  idempotency hit observable

bypass redis-frontgate:
  waiting room enabled
  no active token
  request rejected with ACTIVE_TOKEN_REQUIRED
  DB reserved count unchanged
  Redis stock unchanged

failure redis-frontgate:
  injected failure after Redis gate before DB save
  retryable failure returned
  compensation success count increments
  DB reserved count and Redis stock remain aligned

normal rdb-atomic:
  same stock and user shape
  gap = 0
  oversell = 0
  unexpected = 0
```

### Load Matrix

The first official matrix should be small enough to finish quickly and large enough to show direction.

Candidate first pass:

```text
strategies:
  redis-frontgate
  rdb-atomic

scenarios:
  direct burst, waiting-room.enabled=false
  waiting-room-admitted, waiting-room.enabled=true
  duplicate/idempotency
  failure injection

loads:
  direct burst: 1000 users, stock 100
  waiting-room-admitted: reuse v3.1-safe admission size first
  failure injection: 100 users, stock 100, failure limit 10
  duplicate: 100 users x 2 requests
```

Expansion only if the first matrix is stable:

```text
direct burst: 3000 or 5000 users
waiting-room-admitted: vary admitted users by batchSize / activeCapacity
```

Do not jump to very large load until the correctness counters are stable.

---

## Comparison Metrics

Minimum correctness conditions:

```text
oversell_count = 0
decision_reservation_gap = 0
unexpected_responses = 0
duplicate_reservation_count = 0
compensation_failure_count = 0
```

Latency:

```text
HTTP p95 / p99
reservation request p95 / p99
reservation save p95 / p99
stock/front-gate decision p95 / p99
```

Business counters:

```text
reservation attempts
reservation success
idempotency hit
duplicate rejected
sold out
active-token rejection
front-gate accepted
front-gate rejected by reason
compensation success/failure
active-token restored
DB RESERVED count
Redis available stock
```

Interpretation rule:

```text
redis-frontgate is not automatically better if p95/p99 is lower.
It is valuable only if it meaningfully reduces DB-bound work or failure amplification enough to justify Redis marker, TTL, and compensation complexity.

rdb-atomic is not automatically worse if it sends more work to DB.
It may be preferable when v3.1 waiting-room admission keeps DB load within a safe range and simpler correctness is more important than offloading every rejected request.
```

---

## Expected Portfolio Conclusion Shape

The result should be written conditionally:

```text
v2 showed Redis Lua can protect a hot stock counter under direct burst.
v3.1 reduced entry pressure before purchase through active tokens.
The first v3.2 implementation proved consistency recovery but also showed that Redis stock-decision-only loses value when DB idempotency and duplicate reads happen before Redis.
The final v3.2 comparison therefore tests Redis as a true front gate against the simpler RDB atomic path.
```

Possible outcomes:

```text
If redis-frontgate wins:
  Redis is useful not merely because it is fast, but because it prevents unnecessary DB reservation work before it starts.
  The tradeoff is extra marker state, TTL policy, compensation, and known crash-window limitations.

If rdb-atomic wins or is close:
  After waiting-room admission, the simpler DB-truth path may be enough for this system size.
  Redis front gate is not free; without a clear DB-protection benefit, it may be overengineering.
```

Both outcomes are portfolio-valid if the measurements are clear.
