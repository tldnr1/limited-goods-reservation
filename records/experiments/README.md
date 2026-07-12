# Experiments

This directory stores measured experiment evidence. The root [README](../../README.md) summarizes the project progression; records here own the exact setup, metrics, interpretation, limitations, and decision impact.

## Evidence Index

| Version | Record | Purpose |
| --- | --- | --- |
| v1 | [Oversell baseline](v1-oversell-baseline.md) | Reproduce oversell and lost updates with the naive RDB flow. |
| v2 | [Stock strategy comparison](v2-stock-strategy-comparison.md) | Compare naive RDB, RDB atomic, RDB pessimistic, and Redis Lua in the 60-run primary matrix. |
| v2 | [Expansion rerun](v2-stock-strategy-expansion-rerun.md) | Recheck Redis Lua and RDB atomic at 3,000/5,000/10,000 users with stabilization waits. |
| v2 | [Failure injection](v2-stock-failure-injection.md) | Measure the Redis-to-DB dual-write gap after an injected DB persistence failure. |
| v3.1 | [Entry control](v3-1-entry-control.md) | Compare direct, fixed-batch, and hybrid waiting-room policies, including think time. |
| v3.1 | [Initial entry-control run](v3-1-entry-control-initial.md) | Preserve the first measured waiting-room baseline and its limitations. |
| v3.2 | [Reservation load baseline](v3-2-reservation-load-baseline.md) | Verify reservation, idempotency, and compensation before moving Redis forward as a front gate. |
| v3.2 | [Architecture load comparison](v3-2-architecture-load-comparison.md) | Compare Redis front gate and RDB atomic across normal, sold-out, duplicate, and failure scenarios. |

CSV files with matching names contain the committed measured rows when raw tabular evidence is part of the record. Local raw k6 summaries belong under `notes/` and are not project Truth.

## Record Policy

Commit an experiment record to `dev` and `main` when it supports an architectural decision or version result. Do not reinterpret an older measured result when a later version changes direction; add a new record and connect the decision explicitly.

Use this structure:

```text
# Experiment Title

## Version
## Question
## Alternatives
## Comparison Criteria
## Setup
## Metrics
## Result Summary
## Decision
## Why This Decision Fits This Project
## Limitations
## Follow-up
```
