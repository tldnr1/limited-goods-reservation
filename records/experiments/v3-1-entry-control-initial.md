# v3.1 Entry Control Initial Load Test

## Version

v3.1

## Question

Does a Redis waiting room with active tokens reduce the number of requests that reach the purchase path under the same burst shape?

## Setup

Date:

```text
2026-07-07
```

Commands:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Smoke
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Users 1000 -Repeats 1 -MaxPolls 10 -SkipBuild
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Policies hybrid -Users 1000 -Repeats 1 -HybridBatchSize 20 -HybridActiveCapacity 10 -MaxPolls 10 -ResultName v3-1-entry-control-capacity-sensitivity -SkipBuild
```

Common setup:

```text
stock strategy: redis-lua
productId: 1
initial stock: 100
users: 1000
repeats: 1
maxPolls: 10
admission interval: 1000 ms
token TTL: 60 seconds
```

Result files:

```text
records/experiments/v3-1-entry-control-smoke.csv
records/experiments/v3-1-entry-control-initial.csv
records/experiments/v3-1-entry-control-capacity-sensitivity.csv
```

Raw k6 summaries:

```text
notes/v3-1-entry-control/raw/
```

## Scenario

Compared policies:

```text
direct:
  waiting room disabled

fixed:
  batchSize=20
  activeCapacity=10000

hybrid:
  batchSize=20
  activeCapacity=100

hybrid capacity sensitivity:
  batchSize=20
  activeCapacity=10
```

## Metrics

Primary metrics:

```text
purchase_attempts
not_admitted_within_window
purchase_p95_ms
purchase_p99_ms
unexpected_responses
oversell_count
decision_order_gap
```

Interpretation rule:

```text
http_reqs includes waiting-room enter/status polling requests.
Use purchase_attempts and purchase_req_duration when comparing pressure on the purchase path.
```

## Result Summary

Initial 1000-user matrix:

| policy | batch | capacity | purchase_attempts | not_admitted | success | sold_out | purchase_p95_ms | purchase_p99_ms | unexpected | oversell | gap |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| direct | 0 | 0 | 1000 | 0 | 100 | 900 | 680.01 | 699.89 | 0 | 0 | 0 |
| fixed | 20 | 10000 | 200 | 800 | 100 | 100 | 345.23 | 356.05 | 0 | 0 | 0 |
| hybrid | 20 | 100 | 200 | 800 | 100 | 100 | 353.79 | 368.08 | 0 | 0 | 0 |

Capacity sensitivity:

| policy | batch | capacity | purchase_attempts | not_admitted | success | sold_out | purchase_p95_ms | purchase_p99_ms | unexpected | oversell | gap |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| hybrid | 20 | 10 | 100 | 900 | 100 | 0 | 270.86 | 277.93 | 0 | 0 | 0 |

## Decision Impact

The initial result supports the v3.1 direction:

```text
direct access sent all 1000 users into the purchase path.
fixed and default hybrid admitted 200 users into the purchase path within the 10-poll window.
hybrid with activeCapacity=10 admitted only 100 users, proving activeCapacity can act as a stricter admission cap.
```

The default fixed and hybrid runs produced the same purchase_attempts because active users purchased immediately and consumed their token quickly. In this request shape, `batchSize=20` is the binding limit, not `activeCapacity=100`.

## Limitations

This is an initial one-repeat run, not the final v3.1 official matrix.

The test uses immediate purchase after ACTIVE. A real UI may have more user think time between ACTIVE and purchase, which would make activeCapacity more important.

`http_failed_rate` is not the primary comparison metric because expected `SOLD_OUT` 409 responses are counted as failed HTTP requests by k6. Use response-code counters and business metrics instead.

`waiting_queue_size_after` and `active_token_current_after` are post-run snapshots. The scheduler can continue issuing active tokens after k6 users stop polling, so these values are useful for troubleshooting but should not replace k6 counters.

## Follow-up

Completed in the final v3.1 entry-control record:

```text
records/experiments/v3-1-entry-control.md
```

The final record adds think-time scenarios and closes v3.1 as the entry-control baseline.
