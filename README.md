# limited-goods-reservation

Spring Boot backend portfolio project for limited goods sale failures.

Core message:

```text
This is not a full commerce project.
This project reproduces and improves limited-sale backend failures step by step.
```

---

## Current Status

The project is in **v2 stock strategy comparison foundation** stage.

The default v2 adapter still uses the v1 naive RDB read-check-write flow so strategy experiments can compare against the same failure baseline.

---

## Quick Check

Run the basic test suite:

```text
./gradlew test
```

Run the Docker Compose API:

```text
docker compose up --build
```

Run the v2 stock strategy baseline through the Compose profile:

```text
docker compose --profile load-test up --build k6
```

Run smaller load steps by overriding k6 environment values:

```text
$env:VUS='10'; $env:ITERATIONS='10'; docker compose --profile load-test up --force-recreate k6
$env:VUS='100'; $env:ITERATIONS='100'; docker compose --profile load-test up --force-recreate k6
$env:VUS='1000'; $env:ITERATIONS='1000'; docker compose --profile load-test up --force-recreate k6
```

Select a v2 stock strategy with `STOCK_STRATEGY`:

```text
$env:STOCK_STRATEGY='naive-rdb'; docker compose up -d --force-recreate api
$env:STOCK_STRATEGY='naive-rdb'; docker compose --profile load-test up --force-recreate k6
```

Open local monitoring:

```text
Prometheus: http://localhost:9090
Grafana: http://localhost:3000
Grafana login: admin / admin
Dashboard: Limited Goods / v2 Stock Strategy Overview
```

Verify oversell after the k6 run:

```sql
SELECT p.id AS product_id,
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

---

## Documents

Start with `AGENTS.md`. It is the current Codex work index.

Stable project rules live here:

```text
docs/01-roadmap.md
docs/02-domain-data.md
docs/03-architecture.md
docs/04-verification-experiments.md
docs/05-workflow-future-scope.md
```

Use README for quickstart. Use AGENTS/docs for implementation guidance.
