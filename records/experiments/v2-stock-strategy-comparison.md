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

```text
to be filled after the 60-run matrix is completed
```

## Decision

```text
to be filled after comparison
```

## Why This Decision Fits This Project

```text
to be filled after comparison
```

## Limitations

Redis Lua uses `stock:available:{productId}` as the stock decision source of truth in v2. The API still persists a DB order synchronously before responding, so HTTP latency includes both Redis stock decision time and DB order save time.

The v2 comparison does not inject DB write failures after Redis deduction. Compensation, reservation state, payment delay, and reconciliation remain v3+ scope.

## Follow-up

```text
Summarize the CSV into strategy/load averages.
Compare Redis Lua stock decision p95/p99 against full HTTP p95/p99.
Use the selected direction as the v3 waiting room / active token baseline.
```
