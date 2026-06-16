# ADR 0001. Use Layered Stock Strategy Structure for v2

## Status

Accepted

## Context

v1 intentionally used a simple feature-based N-tier structure to reproduce the naive purchase oversell problem. v2 originally introduced focused port/adapter terminology around stock consistency, but the project goal for v2 is not to demonstrate full architecture. The goal is to compare stock consistency strategies in a way that is easy to explain in a portfolio.

The official v2 comparison needs to cover:

```text
naive-rdb
rdb-atomic
rdb-pessimistic
redis-lua
```

Redis Lua is important because it represents a Redis-first stock decision. However, the v2 API still saves the order synchronously in PostgreSQL, so HTTP latency and stock decision latency must be interpreted separately.

## Decision

Use a v1-like layered package structure for v2:

```text
purchase/controller
purchase/dto
purchase/service
purchase/metrics
product/entity
product/repository
order/entity
order/repository
stock/strategy
global
```

Only the stock deduction decision is isolated behind `StockDeductionStrategy`. `PurchaseService` keeps the shared purchase flow:

```text
validate request
-> selected stock strategy deducts stock
-> save order
-> return response
```

## Consequences

The v2 code is easier to read from v1 and easier to explain as an experiment. Strategy replacement remains simple through `STOCK_STRATEGY`.

This intentionally postpones broader port/adapter or strict DDD structure until v3, when waiting room, active token, payment worker, and other external boundaries make those abstractions easier to justify.

Redis Lua has a documented limitation in v2: Redis is the stock decision source of truth, but DB order persistence still happens synchronously after deduction. Compensation for DB write failure after Redis deduction is future scope.
