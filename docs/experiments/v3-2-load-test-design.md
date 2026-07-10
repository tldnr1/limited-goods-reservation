# v3.2 Load Test Design

## Version

v3.2

## Question

After v3.1 entry control, is a simple RDB atomic reservation flow enough, or does a Redis front gate justify its extra marker, TTL, finalize, and compensation cost?

## Primary Hypothesis

With the same request shape and the same DB connection pool, both architectures should preserve correctness:

```text
oversell_count = 0
decision_reservation_gap = 0
unexpected_responses = 0
```

Redis front gate may reduce DB work and tail latency by rejecting sold-out, duplicate, or in-flight requests before the reservation transaction reaches PostgreSQL.

## Architecture Candidates

```text
redis-frontgate
rdb-atomic
```

The comparison must run from one codebase and switch only:

```text
PURCHASE_ARCHITECTURE=redis-frontgate
PURCHASE_ARCHITECTURE=rdb-atomic
```

## Control Variables

Keep these fixed in the primary architecture comparison:

```text
code commit
Docker Compose services
single hot product
productId = 1
baseline stock = 100
Hikari max pool size = 10
Hikari connection timeout = 30000 ms
PostgreSQL schema and indexes
Redis version
k6 script and summary format
Prometheus remote write setting
```

The primary VU baseline isolates the reservation architecture with:

```text
WAITING_ROOM_ENABLED=false
WAITING_ROOM_ADMISSION_SCHEDULER_ENABLED=false
```

Waiting-room admission and active-capacity sweeps are separate experiments. Do not mix them into the first architecture baseline.

## Load Levels

Use these as the official local Docker Desktop range:

```text
1000 VU
3000 VU
5000 VU
```

Use 10000 VU only as a local stress or harness-limit observation. Do not use it as the main architecture decision point unless the k6 generator sanity result is also reported beside it.

Do not claim million-level concurrent purchase capacity from this local setup. That would require a distributed load generator and separate server resources.

## Scenarios

### normal

```text
stock = 100
users = load level
duplicate requests = 1
failure injection = off
```

Purpose:

```text
oversubscription correctness and baseline tail latency
```

### sold-out

```text
stock = 0
users = load level
duplicate requests = 1
failure injection = off
```

Purpose:

```text
measure how cheaply each architecture rejects already sold-out traffic
```

### duplicate

```text
stock = max(100, users)
users = load level
duplicate requests = 2
failure injection = off
```

Purpose:

```text
isolate idempotency and duplicate handling without sold-out noise
```

### failure

```text
stock = 100
users = load level
failure mode = AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE
failure limit = 10
```

Purpose:

```text
verify retryable failure handling and Redis compensation under load
```

## DB Pool Sweep

Hikari pool size is a valid performance variable, especially for rdb-atomic. It should not be mixed into the primary architecture comparison.

Use this order:

```text
1. Primary comparison with Hikari max pool size fixed at 10.
2. Pool sweep as a follow-up explanation axis.
```

Recommended pool sweep:

```text
architectures = rdb-atomic
scenarios = normal,sold-out
users = 3000 or 5000
pool sizes = 5,10,20,40
```

If rdb-atomic improves materially as the pool grows, interpret the primary comparison as:

```text
Redis front gate reduced DB pressure under the fixed pool condition.
rdb-atomic may recover some latency with a larger pool, but that shifts cost to DB connection capacity.
```

## Metrics

k6:

```text
http_req_failed
http_req_duration p50/p95/p99
reservation_req_duration p50/p95/p99
reservation_attempts
reservation_created
reservation_reused
sold_out_responses
already_reserved_responses
retryable_failure_responses
idempotency_processing_responses
unexpected_responses
```

DB and Redis:

```text
db_sold_quantity
db_reserved_count
duplicate_reservation_count
redis_available
stock_decision_count
decision_reservation_gap
oversell_count
```

Actuator counters:

```text
reservation.idempotency.hit
reservation.duplicate.rejected
reservation.front-gate.accepted
reservation.front-gate.rejected
reservation.compensation.success
reservation.compensation.failure
```

Environment:

```text
hikari_max_pool_size
hikari_connection_timeout_ms
waiting_room_enabled
active_capacity
```

## Decision Criteria

Correctness gate:

```text
oversell_count must be 0
decision_reservation_gap must be 0
unexpected_responses must be 0
```

Performance signal:

```text
Redis front gate has meaningful latency benefit if p95 or p99 improves by about 20-30% at the same VU level.
```

DB protection signal:

```text
Redis front gate has meaningful DB-protection benefit if it sharply reduces DB-reaching work under sold-out or duplicate traffic.
```

Architecture decision:

```text
Choose rdb-atomic when correctness is equal and latency/DB-protection gains are small.
Choose redis-frontgate only when the measured gains justify marker, TTL, finalize, compensation, and Redis-DB gap handling.
```

## Runners

Primary VU matrix:

```powershell
.\scripts\v3-2\run-architecture-vu-matrix.ps1
```

Small syntax/check run:

```powershell
.\scripts\v3-2\run-architecture-vu-matrix.ps1 -SkipBuild -Users "1000" -Scenarios "normal" -Architectures "redis-frontgate"
```

Pool sweep:

```powershell
.\scripts\v3-2\run-architecture-pool-sweep.ps1
```

## Known Limitations

The local Docker Desktop setup shares CPU, memory, and I/O across API, PostgreSQL, Redis, Prometheus, and k6. The k6 generator sanity result showed 10000 VU enters a harness-limit zone. Treat 10000 VU as a stress note, not a primary architecture decision point.
