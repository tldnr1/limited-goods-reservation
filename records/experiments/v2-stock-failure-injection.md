# v2 Stock Failure Injection

## Version

v2 stock strategy failure-mode comparison

## Question

When stock decision succeeds but order persistence fails before `orderRepository.save`, does each strategy leave stock and order state consistent?

## Alternatives

```text
rdb-atomic
redis-lua
```

## Failure Mode

```text
AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE
```

The failure injector throws an intentional API exception after `StockDeductionStrategy.deduct(productId)` succeeds and before the common order save path runs.

This experiment is not a high-load capacity test. It is a consistency test for the gap between stock decision and order persistence.

## Setup

```text
initial stock = 100
productId = 1
loads = 500, 1000 users
repeats = 5 per strategy/load
failure_limit = 10 per run_id
executor = k6 shared-iterations
official runtime = Windows Docker Compose
```

Run commands:

```powershell
.\scripts\v2\run-stock-failure-injection-matrix.ps1 -Strategy rdb-atomic -Users "500,1000" -Repeats 5 -SkipBuild
.\scripts\v2\run-stock-failure-injection-matrix.ps1 -Strategy redis-lua -Users "500,1000" -Repeats 5 -SkipBuild
```

Raw CSV:

```text
records/experiments/v2-stock-failure-injection.csv
```

Raw k6 summaries:

```text
notes/v2-stock-strategy/raw/
```

## Result Summary

Result as of run IDs starting `20260617`:

```text
measured rows = 20
strategies = rdb-atomic, redis-lua
loads = 500, 1000 users
repeats = 5 per strategy/load
unexpected_responses = 0 in all measured runs
injected_failure_responses = 10 in all measured runs
oversell_count = 0 in all measured runs
```

| strategy | users | runs | avg success | avg sold out | avg injected failure | sum unexpected | avg stock decision | avg orders | avg gap | avg HTTP p50 ms | avg HTTP p95 ms | avg HTTP p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rdb-atomic | 500 | 5 | 100.0 | 390.0 | 10.0 | 0 | 100.0 | 100.0 | 0.0 | 529.47 | 680.41 | 700.49 |
| rdb-atomic | 1000 | 5 | 100.0 | 890.0 | 10.0 | 0 | 100.0 | 100.0 | 0.0 | 495.72 | 628.50 | 672.54 |
| redis-lua | 500 | 5 | 90.0 | 400.0 | 10.0 | 0 | 100.0 | 90.0 | -10.0 | 243.22 | 377.88 | 403.07 |
| redis-lua | 1000 | 5 | 90.0 | 900.0 | 10.0 | 0 | 100.0 | 90.0 | -10.0 | 218.52 | 369.13 | 408.10 |

## Interpretation

RDB atomic preserved the stock/order relationship under the injected failure:

```text
stock_decision_count = 100
db_order_count = 100
decision_order_gap = 0
```

The stock update and order save belong to the same database transaction, so injected failures after the atomic stock update roll back with the transaction. Later successful requests can still fill the stock to 100 orders.

Redis Lua exposed the expected dual-write gap:

```text
stock_decision_count = 100
db_order_count = 90
decision_order_gap = -10
```

Redis stock deduction succeeds before DB order persistence. When the API fails after Redis deduction but before DB order save, Redis remains the stock source of truth and the DB has fewer orders than stock decisions.

This is not an oversell failure. It is a lost-order / orphan-stock-decision failure mode.

## Decision

Redis Lua remains the stronger normal-load burst candidate, but it requires v3 compensation or reconciliation before it can be treated as operationally complete.

RDB atomic remains the simpler correctness baseline. It is less attractive under the previous high-burst normal-load result, but it handles this injected DB persistence failure cleanly because the stock update and order save share one DB transaction.

## Burst Scope

This experiment intentionally stops at 500 and 1000 users.

The goal is to isolate failure semantics, not capacity. Higher burst loads such as 3000, 5000, or 10000 can introduce real timeout or host-machine variance, which would make the injected failure harder to interpret. Failure plus burst should be tested later after v3 defines compensation, reconciliation, idempotency, or reservation state.

## Implementation Note

During setup, passing `-Users 500,1000` through `powershell -File` was parsed as a single `5001000` value. The failure matrix runner now parses user counts as strings and splits comma-separated values itself. Use quoted input in scripts:

```powershell
-Users "500,1000"
```

## Follow-up

Use this result to design v3 failure handling:

```text
Redis Lua compensation after DB write failure
reconciliation between Redis stock decisions and DB orders
idempotency key for retry after timeout
reservation state or TTL if stock decisions should be reversible
```
