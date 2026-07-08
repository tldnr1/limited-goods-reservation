# v3.2 Reservation Load Baseline

## Version

v3.2

## Question

After adding reservation, idempotency, and compensation, how does the current Redis Lua reservation path compare with the RDB atomic control baseline?

This record captures the current v3.2 baseline before the Redis front-gate redesign discussion. It records measured behavior without treating the result as a final strategy selection.

## Alternatives

```text
redis-lua with reservation/idempotency/compensation
rdb-atomic with reservation/idempotency/compensation
```

## Comparison Criteria

```text
decision_reservation_gap
oversell_count
unexpected_responses
retryable_failure_responses
compensation_success_metric
idempotency_hit_metric
reservation p95/p99 latency
```

## Setup

Date:

```text
2026-07-08
```

Common setup:

```text
Docker Compose services: api, postgres, redis, prometheus, k6
productId: 1
initial stock: 100
waiting room: disabled
```

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-2\run-reservation-load-matrix.ps1 -SkipBuild
```

Result file:

```text
records/experiments/v3-2-reservation-load-baseline.csv
```

## Result Summary

| scenario | strategy | users | created | sold out | reused | retryable | gap | oversell | p95 ms | p99 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| normal | redis-lua | 1000 | 100 | 900 | 0 | 0 | 0 | 0 | 2487.89 | 2745.12 |
| normal | rdb-atomic | 1000 | 100 | 900 | 0 | 0 | 0 | 0 | 1659.16 | 1803.32 |
| failure | redis-lua | 100 | 90 | 0 | 0 | 10 | 0 | 0 | 623.58 | 636.57 |
| failure | rdb-atomic | 100 | 90 | 0 | 0 | 10 | 0 | 0 | 771.51 | 798.41 |
| duplicate | redis-lua | 100 | 100 | 0 | 100 | 0 | 0 | 0 | 694.61 | 747.47 |

Additional observations:

```text
redis-lua failure:
  compensation_success_metric = 10
  compensation_failure_metric = 0

redis-lua duplicate:
  idempotency_hit_metric = 100
  duplicate_reservation_count = 0
```

## Observations

The v3.2 consistency goal was met in this baseline matrix:

```text
decision_reservation_gap = 0
oversell_count = 0
unexpected_responses = 0
```

Compared with the v2 Redis Lua failure injection, the important improvement is:

```text
v2 redis-lua failure injection:
  gap = -10

v3.2 redis-lua baseline failure:
  retryable failures = 10
  compensation successes = 10
  gap = 0
```

Normal-load latency favored the RDB atomic control baseline in this run:

```text
redis-lua normal p95 = 2487.89 ms
rdb-atomic normal p95 = 1659.16 ms
```

In this v3.2 shape, Redis Lua is no longer obviously faster because the request still performs DB idempotency checks, DB duplicate checks, and DB reservation inserts. Redis Lua also adds a Redis round trip before the DB reservation write.

## Follow-up Question

This baseline suggests the final portfolio conclusion should not say "Redis Lua is always better."

A more useful follow-up question is:

```text
After v3.1 waiting-room entry control, does Redis Lua still need to be the stock-decision main path?
If Redis is used, should it remain a stock-decision component or move forward as a front gate
that checks active token, idempotency, duplicate user/product state, and stock before DB writes?
```

## Limitations

```text
single repeat only
single API container
single Redis
single PostgreSQL
local Docker Desktop environment
waiting room disabled to isolate reservation consistency
normal load tested at 1000 users only in this baseline
```

## Follow-up

Before finalizing the next v3.2 decision:

```text
write an ADR for Redis front-gate scope and RDB atomic control retention
review whether normal-load comparison should be repeated 3-5 times after the redesign
compare Redis front-gate and RDB atomic with waiting room enabled and bypass scenarios separated
```
