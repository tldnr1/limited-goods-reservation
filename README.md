# limited-goods-reservation

Spring Boot backend portfolio project for limited goods sale failures.

Core message:

```text
This is not a full commerce project.
This project reproduces and improves limited-sale backend failures step by step.
```

---

## Current Status

The project is in **v1 naive purchase baseline** stage.

v1 reproduces oversell with a deliberately naive RDB read-check-write purchase flow.

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

Run the v1 k6 oversell baseline through the Compose profile:

```text
docker compose --profile load-test up --build k6
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
