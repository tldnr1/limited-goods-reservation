# v1 Oversell Baseline

## Version

v1 naive purchase baseline

## Question

Can a deliberately naive RDB read-check-write purchase flow reproduce oversell or inventory inconsistency for a single hot product?

## Setup

Date:

```text
2026-06-09
```

Runtime:

```text
Docker Compose
Spring Boot API + PostgreSQL + k6
```

Seed:

```text
productId = 1
initial_quantity = 100
```

Verification commands:

```text
docker compose build api
docker compose up -d api
Invoke-RestMethod -Uri http://localhost:8080/actuator/health
docker compose --profile load-test up --force-recreate k6
```

DB verification query:

```sql
SELECT
    p.id AS product_id,
    ps.initial_quantity,
    ps.sold_quantity,
    COUNT(o.id) AS successful_order_count,
    GREATEST(COUNT(o.id) - ps.initial_quantity, 0) AS oversell_count,
    GREATEST(COUNT(o.id) - ps.sold_quantity, 0) AS order_stock_gap
FROM products p
JOIN product_stock ps ON ps.product_id = p.id
LEFT JOIN orders o ON o.product_id = p.id
WHERE p.id = 1
GROUP BY p.id, ps.initial_quantity, ps.sold_quantity;
```

## Scenario

```text
k6 script: k6/v1/oversell-baseline.js
executor: shared-iterations
vus: 1000
iterations: 1000
request: POST /api/v1/purchases
header: X-USER-ID = distinct user id per iteration
body: {"productId":1}
```

## Metrics

| Metric | Value |
| --- | ---: |
| Health status | UP |
| Successful purchase responses | 973 |
| Sold out responses | 27 |
| Unexpected responses | 0 |
| DB initial_quantity | 100 |
| DB sold_quantity | 97 |
| DB successful_order_count | 973 |
| DB oversell_count | 873 |
| DB order_stock_gap | 876 |

## Result Summary

The v1 baseline reproduced oversell. 973 purchase attempts received a successful response even though the initial stock was 100.

The final DB state also showed inventory inconsistency: `orders` had 973 rows for `productId=1`, while `product_stock.sold_quantity` ended at 97 because concurrent transactions overwrote each other with stale read-check-write values.

## Decision Impact

The naive RDB read-check-write flow is not a viable stock consistency strategy. v2 should compare focused stock consistency alternatives using the same hot-product scenario.

## Limitations

This is a local Docker Compose result from one baseline run. The purpose was failure reproduction, not latency tuning or production sizing.

## Follow-up

Start v2 stock strategy comparison only when that version begins. Candidate alternatives remain RDB atomic update, RDB pessimistic lock, RDB optimistic lock, Redis distributed lock, and Redis Lua.
