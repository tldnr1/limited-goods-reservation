# v2 Naive RDB Baseline

> Historical note: this result was recorded before v2 was simplified to a v1-like layered + stock strategy structure. Keep it as the first v2 harness baseline. The official strategy comparison should use `records/experiments/v2-stock-strategy-comparison.md`.

## Version

v2 stock strategy comparison

## Question

What is the control baseline for the v2 strategy comparison when the selected stock strategy is still the v1-style naive RDB read-check-write flow?

## Alternatives

```text
selected strategy: naive-rdb
future comparison targets: rdb-atomic, rdb-pessimistic, rdb-optimistic, redis-lock, redis-lua
```

## Comparison Criteria

```text
oversell_count
order_stock_gap
successful purchase count
failure reason count
p50 / p95 / p99 latency
Prometheus / Grafana visibility
```

## Setup

Date:

```text
2026-06-10
```

Runtime:

```text
Docker Compose
Spring Boot API + PostgreSQL + Redis + Prometheus + Grafana + k6
```

Seed:

```text
productId = 1
initial_quantity = 100
STOCK_STRATEGY = naive-rdb
```

Verification commands:

```text
docker compose up -d --force-recreate api redis prometheus grafana
docker compose --profile load-test up --force-recreate k6
```

Dashboard:

```text
Grafana: http://localhost:3000
Login: admin / admin
Dashboard: Limited Goods / v2 Stock Strategy Overview
```

## Metrics

k6 summary:

| Metric | Value |
| --- | ---: |
| VUs | 1000 |
| Iterations | 1000 |
| HTTP requests | 1001 |
| HTTP failed rate | 0.030969030969030968 |
| p50 latency | 1396.848443 ms |
| p95 latency | 2061.781318 ms |
| p99 latency | 2366.142083 ms |
| Successful purchases | 969 |
| SOLD_OUT responses | 31 |
| OPTIMISTIC_CONFLICT responses | 0 |
| LOCK_BUSY responses | 0 |
| LOCK_TIMEOUT responses | 0 |
| Unexpected responses | 0 |

DB verification:

| Metric | Value |
| --- | ---: |
| product_id | 1 |
| initial_quantity | 100 |
| sold_quantity | 100 |
| successful_order_count | 969 |
| oversell_count | 869 |
| order_stock_gap | 869 |

Prometheus checks:

| Query | Value |
| --- | ---: |
| `sum(purchase_attempts_total)` | 1000 |
| `sum(purchase_success_total)` | 969 |
| `sum by (reason) (purchase_failure_total)` / SOLD_OUT | 31 |
| `max(k6_http_req_duration_p99)` | 2.36678612684 seconds |

## Result Summary

The v2 naive-rdb control baseline reproduced oversell under the shared v2 experiment harness.

The API returned 969 successful purchases for an initial stock of 100, so the DB-level `oversell_count` was 869. The stock row ended at `sold_quantity = 100`, while `orders` contained 969 rows for `productId = 1`, which preserves the v1 failure mode under the earlier v2 foundation structure.

Latency was high at the tail for the local Docker Compose run:

```text
p95 = 2061.781318 ms
p99 = 2366.142083 ms
```

## Decision

Use this result as the v2 control baseline. Every stock consistency strategy should be compared against this same scenario shape and recorded with the same k6, DB, Prometheus, and Grafana signals.

## Why This Decision Fits This Project

The project goal is to move from failure reproduction to measured strategy comparison. This baseline proves the v2 experiment harness can reproduce the known failure while also recording latency, failure reasons, Prometheus metrics, and dashboard-ready visualization data.

## Limitations

This is a local Docker Compose result from one run on a developer machine. It is useful for comparing local strategy behavior, not for production sizing.

The HTTP failed rate includes 409 SOLD_OUT responses from k6's built-in HTTP status interpretation. The project comparison should use response `code`, k6 custom counters, and Prometheus `purchase_failure_total{reason=...}` for business failure analysis.

## Follow-up

Run strategy branches with the same baseline shape:

Historical note: these branches were used before the v2 comparison code was folded back into `feature/v2`. After that consolidation, they can be deleted once the useful code and records are preserved.

```text
experiment/v2-rdb-atomic
experiment/v2-rdb-pessimistic
experiment/v2-rdb-optimistic-no-retry
experiment/v2-rdb-optimistic-retry-3
experiment/v2-redis-lock
experiment/v2-redis-lua
```
