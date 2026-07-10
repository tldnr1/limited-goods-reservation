# v3.2 Redis Front Gate vs RDB Atomic Load Comparison

## Version

v3.2

## Date

2026-07-10

## Question

After the v3.1 waiting-room work, is the simple RDB atomic reservation path enough, or does a Redis front gate justify its additional marker, TTL, finalize, and compensation logic?

## Setup

```text
branch: feature/v3-2-frontgate
primary commit before run: 5c15151 test: prepare v3.2 architecture load matrix
runner: scripts/v3-2/run-architecture-vu-matrix.ps1
pool sweep runner: scripts/v3-2/run-architecture-pool-sweep.ps1 -SkipBuild
primary result csv: records/experiments/v3-2-architecture-vu-baseline.csv
pool sweep csv: records/experiments/v3-2-pool-sweep.csv
raw k6 summaries: notes/v3-2-architecture-vu/raw/
```

Primary architecture comparison:

```text
architectures: redis-frontgate, rdb-atomic
scenarios: normal, sold-out, duplicate, failure
users: 1000, 3000, 5000
Hikari max pool size: 10
Hikari connection timeout: 30000 ms
waiting room: disabled for reservation-path isolation
stock: 100 for normal/failure, 0 for sold-out, max(100, users) for duplicate
duplicate requests: 2 for duplicate scenario
failure injection: AFTER_STOCK_DECISION_BEFORE_RESERVATION_SAVE, limit 10
```

DB pool sweep:

```text
architecture: rdb-atomic
scenarios: normal, sold-out
users: 3000
Hikari max pool sizes: 5, 10, 20, 40
```

## Correctness Summary

All primary architecture comparison runs passed the correctness gate:

```text
rows: 24
max http_req_failed_rate: 0
max unexpected_responses: 0
max oversell_count: 0
min decision_reservation_gap: 0
max decision_reservation_gap: 0
max compensation_failure_metric: 0
```

All pool sweep runs also kept:

```text
unexpected_responses = 0
oversell_count = 0
decision_reservation_gap = 0
```

## Primary Result

HTTP p95/p99 latency in milliseconds:

| scenario | users | redis p95 | rdb p95 | p95 reduction | redis p99 | rdb p99 | p99 reduction |
|---|---:|---:|---:|---:|---:|---:|---:|
| normal | 1000 | 765.3 | 1476.7 | 48.2% | 802.5 | 1636.4 | 51.0% |
| normal | 3000 | 1312.3 | 2620.9 | 49.9% | 1466.3 | 2796.9 | 47.6% |
| normal | 5000 | 1310.9 | 3955.9 | 66.9% | 1369.4 | 4071.8 | 66.4% |
| sold-out | 1000 | 465.6 | 863.5 | 46.1% | 482.7 | 900.1 | 46.4% |
| sold-out | 3000 | 733.5 | 2219.7 | 67.0% | 815.4 | 2283.2 | 64.3% |
| sold-out | 5000 | 1566.8 | 4411.3 | 64.5% | 1710.7 | 4649.3 | 63.2% |
| duplicate | 1000 | 1639.6 | 2901.4 | 43.5% | 1798.9 | 3035.5 | 40.7% |
| duplicate | 3000 | 2549.1 | 5785.4 | 55.9% | 2732.8 | 6097.4 | 55.2% |
| duplicate | 5000 | 3864.4 | 12675.8 | 69.5% | 4121.7 | 13194.9 | 68.8% |
| failure | 1000 | 736.8 | 1487.8 | 50.5% | 759.5 | 1679.5 | 54.8% |
| failure | 3000 | 1401.2 | 2885.1 | 51.4% | 1463.8 | 3073.2 | 52.4% |
| failure | 5000 | 1663.4 | 3866.1 | 57.0% | 1754.1 | 4007.5 | 56.2% |

Business outcomes matched scenario expectations:

```text
normal: created 100, remaining requests SOLD_OUT
sold-out: created 0, all requests SOLD_OUT
duplicate: created = users, reused = users
failure: retryable failures = 10, created 100, remaining requests SOLD_OUT
```

Redis front gate counters also matched the expected gate behavior:

```text
normal: accepted 100, rejected users - 100
sold-out: accepted 0, rejected users
duplicate: accepted users, rejected users, idempotency hits users
failure: accepted 110, rejected users - 110
```

The failure scenario has accepted 110 because 10 injected persistence failures pass the gate first and are then compensated, after which 100 successful reservations are created.

## DB Pool Sweep

RDB atomic p95/p99 latency in milliseconds at 3000 VU:

| pool | scenario | p95 | p99 | unexpected | oversell | gap |
|---:|---|---:|---:|---:|---:|---:|
| 5 | normal | 2922.3 | 3123.7 | 0 | 0 | 0 |
| 5 | sold-out | 3042.4 | 3152.5 | 0 | 0 | 0 |
| 10 | normal | 2944.9 | 3042.6 | 0 | 0 | 0 |
| 10 | sold-out | 2438.2 | 2588.9 | 0 | 0 | 0 |
| 20 | normal | 2037.5 | 2218.2 | 0 | 0 | 0 |
| 20 | sold-out | 2398.9 | 2605.8 | 0 | 0 | 0 |
| 40 | normal | 2717.5 | 2885.0 | 0 | 0 | 0 |
| 40 | sold-out | 2610.7 | 2726.4 | 0 | 0 | 0 |

The pool sweep confirms that DB connection pool size is a meaningful performance variable for rdb-atomic. However, increasing the pool was not monotonic in this local Docker setup. Pool 20 was the best normal-load point in this run, while pool 40 regressed.

Even with the best observed rdb-atomic pool setting in this sweep, redis-frontgate remained faster at the same 3000 VU shape:

```text
normal 3000:
redis-frontgate p95 = 1312.3 ms
best rdb-atomic pool sweep p95 = 2037.5 ms

sold-out 3000:
redis-frontgate p95 = 733.5 ms
best rdb-atomic pool sweep p95 = 2398.9 ms
```

## Interpretation

Both architectures are correct under the tested conditions. RDB atomic is still a valid simple control path: it preserved oversell_count = 0 and decision_reservation_gap = 0 across all scenarios.

Redis front gate showed a meaningful latency advantage in every primary scenario and load level. The benefit was especially clear when requests were mostly rejected:

```text
sold-out storm
duplicate/idempotency retry storm
failure scenario after compensated gate passes
```

This supports the hypothesis that the benefit is not merely "Redis is faster", but that Redis front gate prevents unnecessary DB reservation work for requests that can be rejected before the transaction path.

## Decision

For v3.2's load-comparison narrative, keep both candidates:

```text
rdb-atomic: simpler correctness baseline
redis-frontgate: higher-complexity candidate with measured tail-latency and DB-protection benefit
```

The measured result justifies treating Redis front gate as a serious final candidate, not merely an over-engineered variant. If the portfolio story prioritizes limited-goods bursts with many sold-out, duplicate, or retry requests, Redis front gate has enough measured benefit to defend its complexity.

If the system priority is simpler operation under a tighter waiting-room active capacity, rdb-atomic remains defensible, but this run shows that it pays a clear tail-latency cost when direct reservation bursts are allowed into the purchase path.

## Limitations

This is a local Docker Desktop experiment. API, PostgreSQL, Redis, Prometheus, and k6 share the same host resources. The result should not be presented as production capacity.

The primary matrix used one measured run per scenario/load/architecture. Repeat runs would be needed for confidence intervals.

The primary matrix intentionally disabled the waiting room to isolate the reservation architecture. A later experiment should re-enable active-token admission and sweep active capacity.

The test is VU/shared-iterations based. Arrival-rate burst scenarios are still needed to model "open at a fixed time" traffic more directly.

Pool sweep was run only for rdb-atomic at 3000 VU normal/sold-out. It is enough to show that pool size matters, but not enough to fully tune PostgreSQL/Hikari.

## Follow-up

```text
1. Repeat the primary matrix if a more statistically stable portfolio table is needed.
2. Add activeCapacity sweep with waiting room enabled.
3. Add arrival-rate burst scenario.
4. Add lower-level DB-reaching work counters if the final report needs stronger DB-protection attribution.
```
