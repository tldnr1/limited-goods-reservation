# v2 Stock Strategy Expansion Rerun

## Version

v2 stock strategy normal-load burst expansion rerun

## Question

After adding per-run health checks plus short stabilization and cooldown waits, does Redis Lua still look more stable than RDB atomic update under high-VU burst load?

## Alternatives

```text
redis-lua
rdb-atomic
```

## Comparison Criteria

```text
HTTP p50 / p95 / p99 latency
success count
sold out count
unexpected response count
stock_decision_count
order_count
oversell_count
decision_order_gap
```

## Setup

```text
initial stock = 100
productId = 1
loads = 3000, 5000, 10000 users
repeats = 5 per strategy/load
executor = k6 shared-iterations
iterations = users
stabilize before each run = 5 seconds
cooldown after each run = 5 seconds
official runtime = Windows Docker Compose
```

Run commands:

```powershell
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy redis-lua -Users @(3000,5000,10000) -Repeats 5 -SkipBuild -ResultName v2-stock-strategy-expansion-rerun -StabilizeSeconds 5 -CooldownSeconds 5
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-atomic -Users @(3000,5000,10000) -Repeats 5 -SkipBuild -ResultName v2-stock-strategy-expansion-rerun -StabilizeSeconds 5 -CooldownSeconds 5
```

## Metrics

Raw CSV:

```text
records/experiments/v2-stock-strategy-expansion-rerun.csv
```

Raw k6 summaries and logs:

```text
notes/v2-stock-strategy/raw/
```

## Result Summary

Result as of 2026-06-16:

```text
measured rows = 30
strategies = redis-lua, rdb-atomic
loads = 3000, 5000, 10000 users
repeats = 5 per strategy/load
correctness_failures = 0
```

| strategy | users | runs | avg success | avg sold out | avg unexpected | sum unexpected | avg stock decision | avg orders | avg oversell | avg gap | avg HTTP p50 ms | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| redis-lua | 3000 | 5 | 100.0 | 2900.0 | 0.0 | 0 | 100.0 | 100.0 | 0.0 | 0.0 | 369.70 | 634.82 | 705.07 |
| redis-lua | 5000 | 5 | 100.0 | 4900.0 | 0.0 | 0 | 100.0 | 100.0 | 0.0 | 0.0 | 279.00 | 448.90 | 484.92 |
| redis-lua | 10000 | 5 | 100.0 | 9900.0 | 0.0 | 0 | 100.0 | 100.0 | 0.0 | 0.0 | 617.29 | 986.20 | 1079.26 |
| rdb-atomic | 3000 | 5 | 100.0 | 2900.0 | 0.0 | 0 | 100.0 | 100.0 | 0.0 | 0.0 | 714.44 | 1040.39 | 1131.62 |
| rdb-atomic | 5000 | 5 | 100.0 | 4900.0 | 0.0 | 0 | 100.0 | 100.0 | 0.0 | 0.0 | 442.25 | 575.13 | 604.61 |
| rdb-atomic | 10000 | 5 | 100.0 | 9430.6 | 469.4 | 2347 | 100.0 | 100.0 | 0.0 | 0.0 | 988.35 | 1555.60 | 35760.16 |

RDB atomic 10000-user details:

| repeat | success | sold out | unexpected | order count | stock decision | gap | HTTP p95 ms | HTTP p99 ms |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 9886 | 14 | 100 | 100 | 0 | 1588.57 | 1670.89 |
| 2 | 100 | 8982 | 918 | 100 | 100 | 0 | 1015.00 | 58795.61 |
| 3 | 100 | 9900 | 0 | 100 | 100 | 0 | 1245.12 | 1425.06 |
| 4 | 100 | 8998 | 902 | 100 | 100 | 0 | 1451.21 | 58770.97 |
| 5 | 100 | 9387 | 513 | 100 | 100 | 0 | 2478.10 | 58138.29 |

Prometheus timer snapshot after the RDB atomic rerun:

```text
stock.decision.duration p95 ~= 6.31 ms
stock.decision.duration p99 ~= 29.44 ms
order.save.duration p95 ~= 4.33 ms
order.save.duration p99 ~= 8.37 ms
```

Redis Lua timer metrics from this rerun were not captured before Docker Compose was reset for the RDB atomic run. Use the CSV HTTP latency and correctness data for this rerun, and capture Prometheus timer snapshots immediately after each strategy in future passes.

## Decision

Redis Lua remains the preferred normal-load burst expansion candidate.

RDB atomic remains the control baseline because it is simple and preserves correctness, but the stabilized rerun reproduced 10000-user request timeouts. The timeouts did not break stock/order correctness, but they are user-visible failures and make RDB atomic less attractive for the v3-oriented burst path.

## Why This Decision Fits This Project

Both strategies preserved stock correctness:

```text
stock_decision_count = 100
order_count = 100
oversell_count = 0
decision_order_gap = 0
```

Redis Lua also preserved response stability:

```text
unexpected_responses = 0 at 3000 / 5000 / 10000 users
```

RDB atomic preserved data correctness but showed response instability at the highest burst load:

```text
unexpected_responses = 2347 total at 10000 users
```

The HTTP latency still is not strictly monotonic by user count, so these numbers should be described as local Docker high-VU burst observations, not production capacity numbers. However, under the same noisy local test shape, Redis Lua had lower p95/p99 at every expanded load and avoided the 10000-user timeout behavior.

## Limitations

This rerun still uses `shared-iterations` with very high VU counts. It is useful for a limited-sale opening burst, but it also includes local k6 VU initialization, Docker Desktop scheduling, and host machine resource variance.

This rerun does not test failure injection. It does not prove Redis Lua is operationally safe under DB write failure after Redis deduction.

## Follow-up

```text
Commit the stabilized runner and rerun records.
For another normal-load pass, either keep this burst shape and capture timer snapshots per strategy, or add a separate arrival-rate scenario.
Next major experiment should be failure injection:
- Redis Lua DECR success followed by DB order save failure
- RDB atomic stock update success followed by DB order save failure
- duplicate/retry behavior
Use those results to design v3 compensation, reservation, idempotency, and reconciliation.
```
