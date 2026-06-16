# v2 Stock Strategy Comparison

## Version

v2 stock strategy comparison

## Question

Which stock consistency strategy should be selected as the v2 main path when the same single-product purchase API is tested under the same load matrix?

## Alternatives

```text
naive-rdb
rdb-atomic
rdb-pessimistic
redis-lua
```

## Comparison Criteria

```text
HTTP p50 / p95 / p99 latency
stock decision p50 / p95 / p99 latency
order save p50 / p95 / p99 latency
success count
sold out count
unexpected failure count
oversell_count
decision_order_gap
```

## Setup

```text
initial stock = 100
productId = 1
loads = 100, 500, 1000 users
repeats = 5 per strategy/load
official runtime = Windows Docker Compose
```

Run commands:

```powershell
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy naive-rdb
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-atomic
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-pessimistic
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy redis-lua
```

Smoke commands:

```powershell
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy naive-rdb -Smoke
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-atomic -Smoke
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-pessimistic -Smoke
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy redis-lua -Smoke
```

## Metrics

Raw CSV:

```text
records/experiments/v2-stock-strategy-comparison.csv
```

Raw k6 summaries and logs:

```text
notes/v2-stock-strategy/raw/
```

## Result Summary

Result as of 2026-06-16:

`naive-rdb` completed 15 measured runs.

| strategy | users | runs | avg success | avg sold out | avg DB sold | avg orders | avg oversell | avg gap | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| naive-rdb | 100 | 5 | 100.0 | 0.0 | 11.0 | 100.0 | 0.0 | 89.0 | 312.86 | 336.97 |
| naive-rdb | 500 | 5 | 500.0 | 0.0 | 56.2 | 500.0 | 400.0 | 443.8 | 1146.43 | 1175.43 |
| naive-rdb | 1000 | 5 | 989.4 | 10.6 | 95.8 | 989.4 | 889.4 | 893.6 | 1422.11 | 1463.92 |

Naive RDB is a valid failure baseline:

```text
measured rows = 15
100 / 500 / 1000 users = 5 runs each
unexpected_responses = 0
decision_order_gap occurred in all 15 runs
oversell_count occurred in 10 runs, clearly at 500 and 1000 users
```

At 100 users, oversell_count is 0 because the order count equals the initial stock. However, the average DB sold quantity is only 11 while the average order count is 100, so lost update and stock/order inconsistency are still visible.

`rdb-atomic` completed 15 measured runs.

| strategy | users | runs | avg success | avg sold out | avg DB sold | avg orders | avg oversell | avg gap | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rdb-atomic | 100 | 5 | 100.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 403.93 | 417.38 |
| rdb-atomic | 500 | 5 | 100.0 | 400.0 | 100.0 | 100.0 | 0.0 | 0.0 | 445.32 | 460.96 |
| rdb-atomic | 1000 | 5 | 100.0 | 900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 410.58 | 426.21 |

RDB atomic update is a valid correctness baseline:

```text
measured rows = 15
100 / 500 / 1000 users = 5 runs each
unexpected_responses = 0
oversell_count = 0 in all 15 runs
decision_order_gap = 0 in all 15 runs
order_count = 100 and db_sold_quantity = 100 in all measured runs
```

`rdb-pessimistic` completed 15 measured runs.

| strategy | users | runs | avg success | avg sold out | avg DB sold | avg orders | avg oversell | avg gap | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rdb-pessimistic | 100 | 5 | 100.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 559.74 | 578.87 |
| rdb-pessimistic | 500 | 5 | 100.0 | 400.0 | 100.0 | 100.0 | 0.0 | 0.0 | 698.53 | 716.33 |
| rdb-pessimistic | 1000 | 5 | 100.0 | 900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 964.91 | 998.77 |

RDB pessimistic lock is correct but slower than the atomic update baseline in this matrix:

```text
measured rows = 15
100 / 500 / 1000 users = 5 runs each
unexpected_responses = 0
lock_timeout_responses = 0
oversell_count = 0 in all 15 runs
decision_order_gap = 0 in all 15 runs
order_count = 100 and db_sold_quantity = 100 in all measured runs
```

`redis-lua` completed 15 measured runs.

| strategy | users | runs | avg success | avg sold out | avg Redis decision | avg orders | avg oversell | avg gap | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| redis-lua | 100 | 5 | 100.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 136.13 | 157.78 |
| redis-lua | 500 | 5 | 100.0 | 400.0 | 100.0 | 100.0 | 0.0 | 0.0 | 361.52 | 385.59 |
| redis-lua | 1000 | 5 | 100.0 | 900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 340.32 | 366.94 |

Redis Lua is correct and has the best HTTP tail latency in this 100-stock matrix:

```text
measured rows = 15
100 / 500 / 1000 users = 5 runs each
unexpected_responses = 0
redis_available = 0 in all measured runs
stock_decision_count = 100 in all measured runs
oversell_count = 0 in all 15 runs
decision_order_gap = 0 in all 15 runs
order_count = 100 in all measured runs
```

Prometheus timer snapshot after the redis-lua run:

```text
stock.decision.duration p95 ~= 9.64 ms
stock.decision.duration p99 ~= 20.69 ms
order.save.duration p95 ~= 9.12 ms
order.save.duration p99 ~= 27.40 ms
```

Overall 60-run comparison:

| strategy | users | runs | avg success | avg sold out | avg stock decision | avg orders | avg oversell | avg gap | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| naive-rdb | 100 | 5 | 100.0 | 0.0 | 11.0 | 100.0 | 0.0 | 89.0 | 312.86 | 336.97 |
| naive-rdb | 500 | 5 | 500.0 | 0.0 | 56.2 | 500.0 | 400.0 | 443.8 | 1146.43 | 1175.43 |
| naive-rdb | 1000 | 5 | 989.4 | 10.6 | 95.8 | 989.4 | 889.4 | 893.6 | 1422.11 | 1463.92 |
| rdb-atomic | 100 | 5 | 100.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 403.93 | 417.38 |
| rdb-atomic | 500 | 5 | 100.0 | 400.0 | 100.0 | 100.0 | 0.0 | 0.0 | 445.32 | 460.96 |
| rdb-atomic | 1000 | 5 | 100.0 | 900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 410.58 | 426.21 |
| rdb-pessimistic | 100 | 5 | 100.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 559.74 | 578.87 |
| rdb-pessimistic | 500 | 5 | 100.0 | 400.0 | 100.0 | 100.0 | 0.0 | 0.0 | 698.53 | 716.33 |
| rdb-pessimistic | 1000 | 5 | 100.0 | 900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 964.91 | 998.77 |
| redis-lua | 100 | 5 | 100.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 136.13 | 157.78 |
| redis-lua | 500 | 5 | 100.0 | 400.0 | 100.0 | 100.0 | 0.0 | 0.0 | 361.52 | 385.59 |
| redis-lua | 1000 | 5 | 100.0 | 900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 340.32 | 366.94 |

Normal-load expansion started with `redis-lua`.

| strategy | users | runs | avg success | avg sold out | avg stock decision | avg orders | avg oversell | avg gap | avg HTTP p50 ms | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| redis-lua | 3000 | 5 | 100.0 | 2900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 606.62 | 909.13 | 1028.84 |
| redis-lua | 5000 | 5 | 100.0 | 4900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 384.03 | 548.99 | 621.55 |
| redis-lua | 10000 | 5 | 100.0 | 9900.0 | 100.0 | 100.0 | 0.0 | 0.0 | 471.51 | 649.45 | 740.96 |

Redis Lua expansion result:

```text
measured rows = 15
3000 / 5000 / 10000 users = 5 runs each
unexpected_responses = 0
stock_decision_count = 100 in all measured runs
order_count = 100 in all measured runs
oversell_count = 0 in all 15 runs
decision_order_gap = 0 in all 15 runs
```

Prometheus timer snapshot after the redis-lua expansion:

```text
stock.decision.duration p95 ~= 6.92 ms
stock.decision.duration p99 ~= 14.02 ms
order.save.duration p95 ~= 11.10 ms
order.save.duration p99 ~= 37.61 ms
```

The HTTP p95/p99 values are not strictly monotonic by user count. The 3000-user runs had higher tail latency than the later 5000/10000-user runs, so compare the expansion against `rdb-atomic` before making a final scaling claim. The correctness result is stable, but latency interpretation should account for k6 VU initialization and local Docker scheduling variance.

The same normal-load expansion was then run for `rdb-atomic`.

| strategy | users | runs | avg success | avg sold out | avg unexpected | avg stock decision | avg orders | avg oversell | avg gap | avg HTTP p50 ms | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rdb-atomic | 3000 | 5 | 100.0 | 2900.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 1029.33 | 1501.12 | 1602.27 |
| rdb-atomic | 5000 | 5 | 100.0 | 4900.0 | 0.0 | 100.0 | 100.0 | 0.0 | 0.0 | 921.85 | 1298.23 | 1430.84 |
| rdb-atomic | 10000 | 5 | 100.0 | 9861.4 | 38.6 | 100.0 | 100.0 | 0.0 | 0.0 | 733.07 | 1206.63 | 12968.30 |

RDB atomic expansion result:

```text
measured rows = 15
3000 / 5000 / 10000 users = 5 runs each
stock_decision_count = 100 in all measured runs
order_count = 100 in all measured runs
oversell_count = 0 in all 15 runs
decision_order_gap = 0 in all 15 runs
unexpected_responses = 0 for 3000 and 5000 users
unexpected_responses = 193 total at 10000 users
```

Prometheus timer snapshot after the rdb-atomic expansion:

```text
stock.decision.duration p95 ~= 10.12 ms
stock.decision.duration p99 ~= 41.63 ms
order.save.duration p95 ~= 6.45 ms
order.save.duration p99 ~= 12.22 ms
```

Expansion comparison:

| users | redis-lua HTTP p95 ms | redis-lua HTTP p99 ms | rdb-atomic HTTP p95 ms | rdb-atomic HTTP p99 ms | rdb-atomic unexpected avg |
|---:|---:|---:|---:|---:|---:|
| 3000 | 909.13 | 1028.84 | 1501.12 | 1602.27 | 0.0 |
| 5000 | 548.99 | 621.55 | 1298.23 | 1430.84 | 0.0 |
| 10000 | 649.45 | 740.96 | 1206.63 | 12968.30 | 38.6 |

In this expansion, both strategies preserved stock/order correctness. Redis Lua had lower HTTP tail latency at every expanded load and produced no unexpected responses. RDB atomic remained correct but showed request timeouts at 10000 users, especially one run with a very large p99. This suggests Redis Lua is the better next candidate for normal-load scaling experiments, while RDB atomic remains the control baseline for simplicity and failure-mode comparison.

## Decision

Interim decision for the next expansion:

```text
Expand redis-lua first for v3-oriented normal-load scaling.
Keep rdb-atomic as the control baseline.
Do not expand naive-rdb or rdb-pessimistic unless a later question needs them.
```

## Why This Decision Fits This Project

`naive-rdb` successfully reproduces the failure mode, but it is not a candidate for extension.

`rdb-atomic` is the strongest simple RDB baseline. It is correct, easy to explain, and should remain the control group when validating whether Redis Lua still earns its extra operational complexity.

`rdb-pessimistic` is correct but has worse tail latency than `rdb-atomic` in this matrix. It does not currently offer a portfolio advantage over atomic update for the single hot-product stock deduction case.

`redis-lua` is correct and shows the best HTTP p95/p99 in the 100-stock matrix and in the first 3000/5000/10000 normal-load expansion. More importantly, it moves the hot stock decision into Redis, which is the shape needed for later v3 concerns such as active token, reservation TTL, compensation, and reconciliation.

## Limitations

Redis Lua uses `stock:available:{productId}` as the stock decision source of truth in v2. The API still persists a DB order synchronously before responding, so HTTP latency includes both Redis stock decision time and DB order save time.

The v2 comparison does not inject DB write failures after Redis deduction. Compensation, reservation state, payment delay, and reconciliation remain v3+ scope.

## Follow-up

```text
Normal-load expansion for redis-lua and rdb-atomic is complete for users = 3000, 5000, 10000.
Stabilized rerun record:
- records/experiments/v2-stock-strategy-expansion-rerun.md
- records/experiments/v2-stock-strategy-expansion-rerun.csv
If another normal-load pass is needed, consider changing the k6 scenario shape before rerunning both strategies.
Add separate failure-injection experiments for Redis Lua and RDB atomic.
For v3 planning, design compensation/reconciliation around Redis Lua's dual-write limitation.
```
