# v3.1 Entry Control

## Version

v3.1

## Question

Can a Redis ZSET waiting room and active-token admission policy reduce burst pressure on the purchase path before stock deduction?

## Setup

Date:

```text
2026-07-07
```

Common setup:

```text
stock strategy: redis-lua
productId: 1
initial stock: 100
users: 1000
repeats: 1
admission interval: 1000 ms
token TTL: 60 seconds
Docker Compose services: api, postgres, redis, prometheus, k6
```

Commands:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Smoke -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Users 1000 -Repeats 1 -MaxPolls 10 -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Policies hybrid -Users 1000 -Repeats 1 -HybridBatchSize 20 -HybridActiveCapacity 10 -MaxPolls 10 -ResultName v3-1-entry-control-capacity-sensitivity -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Policies fixed -Users 1000 -Repeats 1 -ThinkTimes "2,5,10" -FixedBatchSize 30 -FixedActiveCapacity 10000 -MaxPolls 20 -ResultName v3-1-entry-control-think-time -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Policies hybrid -Users 1000 -Repeats 1 -ThinkTimes "2,5,10" -HybridBatchSize 30 -HybridActiveCapacity 100 -MaxPolls 20 -ResultName v3-1-entry-control-think-time -AppendResult -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Policies hybrid -Users 1000 -Repeats 1 -ThinkTimes "2,5,10" -HybridBatchSize 20 -HybridActiveCapacity 100 -MaxPolls 20 -ResultName v3-1-entry-control-think-time -AppendResult -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Policies hybrid -Users 1000 -Repeats 1 -ThinkTimes "2,5,10" -HybridBatchSize 30 -HybridActiveCapacity 200 -MaxPolls 20 -ResultName v3-1-entry-control-think-time -AppendResult -SkipBuild
```

Result files:

```text
records/experiments/v3-1-entry-control-smoke.csv
records/experiments/v3-1-entry-control-initial.csv
records/experiments/v3-1-entry-control-capacity-sensitivity.csv
records/experiments/v3-1-entry-control-think-time.csv
```

## Scenario

Compared entry policies:

```text
direct:
  waiting room disabled

fixed:
  activeCapacity is intentionally large
  batchSize controls admission rate

hybrid:
  batchSize controls admission rate
  activeCapacity caps users holding active tokens
```

The think-time matrix adds a delay after ACTIVE and before purchase to model the user reading the page or choosing options.

## Metrics

Primary comparison metrics:

```text
purchase_attempts
not_admitted_within_window
purchase_p95_ms
purchase_p99_ms
unexpected_responses
oversell_count
decision_order_gap
```

Interpretation:

```text
http_reqs includes waiting-room enter/status polling.
purchase_attempts is the main pressure metric for the purchase path.
not_admitted_within_window is a controlled waiting-room outcome, not an unexpected failure.
```

## Result Summary

Initial 1000-user entry-control matrix:

| policy | batch | capacity | maxPolls | purchase_attempts | not_admitted | success | sold_out | purchase_p95_ms | purchase_p99_ms | unexpected | oversell | gap |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| direct | 0 | 0 | 10 | 1000 | 0 | 100 | 900 | 680.01 | 699.89 | 0 | 0 | 0 |
| fixed | 20 | 10000 | 10 | 200 | 800 | 100 | 100 | 345.23 | 356.05 | 0 | 0 | 0 |
| hybrid | 20 | 100 | 10 | 200 | 800 | 100 | 100 | 353.79 | 368.08 | 0 | 0 | 0 |
| hybrid | 20 | 10 | 10 | 100 | 900 | 100 | 0 | 270.86 | 277.93 | 0 | 0 | 0 |

Think-time matrix:

| policy | batch | capacity | thinkTime | purchase_attempts | not_admitted | success | sold_out | purchase_p95_ms | purchase_p99_ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| fixed | 30 | 10000 | 2 | 600 | 400 | 100 | 500 | 16.87 | 259.29 |
| fixed | 30 | 10000 | 5 | 600 | 400 | 100 | 500 | 9.98 | 17.67 |
| fixed | 30 | 10000 | 10 | 600 | 400 | 100 | 500 | 11.26 | 19.91 |
| hybrid | 30 | 100 | 2 | 600 | 400 | 100 | 500 | 28.29 | 258.85 |
| hybrid | 30 | 100 | 5 | 360 | 640 | 100 | 260 | 11.10 | 12.04 |
| hybrid | 30 | 100 | 10 | 200 | 800 | 100 | 100 | 10.86 | 11.23 |
| hybrid | 20 | 100 | 2 | 400 | 600 | 100 | 300 | 26.06 | 259.12 |
| hybrid | 20 | 100 | 5 | 340 | 660 | 100 | 240 | 11.48 | 12.04 |
| hybrid | 20 | 100 | 10 | 200 | 800 | 100 | 100 | 10.23 | 13.38 |
| hybrid | 30 | 200 | 2 | 600 | 400 | 100 | 500 | 23.97 | 256.55 |
| hybrid | 30 | 200 | 5 | 600 | 400 | 100 | 500 | 7.33 | 9.53 |
| hybrid | 30 | 200 | 10 | 400 | 600 | 100 | 300 | 7.54 | 9.12 |

All measured runs recorded:

```text
unexpected_responses = 0
oversell_count = 0
decision_order_gap = 0
active_token_required_responses = 0 for normal waiting-room flows
```

## Decision Impact

v3.1 confirms the waiting room goal:

```text
direct access sent all 1000 users into the purchase path.
waiting-room policies reduced purchase_attempts before stock deduction.
batchSize controls admission rate.
activeCapacity becomes important when users hold active tokens for non-trivial think time.
```

The useful sizing heuristic from the experiment is:

```text
expected_active_users ~= batchSize * thinkTimeSeconds / admissionIntervalSeconds
```

If `activeCapacity` is greater than this expected active-user count, hybrid behaves like fixed batch. If `activeCapacity` is lower, it becomes the binding cap and reduces purchase path pressure.

For this single API container and Redis-backed waiting room, `batchSize=30, activeCapacity=100` is a reasonable portfolio baseline:

```text
thinkTime=2s:
  expected active ~= 60
  capacity does not bind

thinkTime=5s:
  expected active ~= 150
  capacity starts to bind

thinkTime=10s:
  expected active ~= 300
  capacity strongly binds
```

## Why This Decision Fits This Project

The project is a learning portfolio, not a full commerce platform. v3.1 should prove entry control before adding reservation, idempotency, compensation, message queues, or infrastructure scaling.

The result gives a concrete story:

```text
v1 reproduced oversell.
v2 compared stock decision strategies and selected Redis Lua.
v3.1 moved burst control in front of Redis stock deduction.
v3.2 can now focus on Redis stock decision to DB truth consistency.
```

## Limitations

The matrix is intentionally local Docker Compose scale:

```text
single API container
single Redis
single PostgreSQL
no Nginx or load balancer
one repeat per measured row
single hot product
```

Nginx/API horizontal scaling is intentionally left out because it answers a different question: whether the entry-control design remains consistent when API containers are scaled out.

`waiting_queue_size_after` and `active_token_current_after` are post-run snapshots. The scheduler may continue issuing tokens after k6 users stop polling, so they are useful for troubleshooting but are not primary decision metrics.

## Follow-up

Move to v3.2:

```text
Redis Lua stock decision remains fast.
PostgreSQL remains the durable truth.
The next problem is the Redis decision -> DB persistence gap under failure, duplicate requests, and compensation.
```
