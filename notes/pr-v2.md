## Version

- [ ] v0
- [ ] v1
- [x] v2
- [ ] v3.1
- [ ] v3.2
- [ ] v4
- [ ] v5+

## Base / Release

Target branch: `main`

Tag after merge: `v2`

## Purpose

Compare stock consistency strategies under the same purchase scenario and select the v3-oriented stock path using measured correctness, latency, and failure behavior.

## Changes

- Isolate stock deduction behind a small strategy interface.
- Add naive RDB, RDB atomic update, RDB pessimistic lock, and Redis Lua strategies.
- Add Micrometer metrics, Prometheus/Grafana monitoring, benchmark runners, and failure injection.
- Finalize the v2 result and advance the project index to v3.1.

## Tests

- [x] Unit test
- [x] Integration test
- [ ] Testcontainers
- [x] k6 load test
- [x] Manual test
- [ ] Not needed

Fresh verification on 2026-06-20:

```text
docker compose build --no-cache api
BUILD SUCCESSFUL
6 actionable Gradle tasks executed
```

## Metrics / Result

```text
official matrix: 4 strategies x 3 loads x 5 repeats = 60 runs
expanded matrix: 2 strategies x 3 loads x 5 repeats = 30 runs
failure matrix: 2 strategies x 2 loads x 5 repeats = 20 runs

normal load:
- redis-lua oversell_count = 0
- redis-lua decision_order_gap = 0

failure injection:
- rdb-atomic decision_order_gap = 0
- redis-lua decision_order_gap = -10
```

Final decision: use Redis Lua as the v3-oriented main path and keep RDB atomic as the control baseline.

## Not Included

- Waiting room and active token implementation
- Compensation, reservation lifecycle, reconciliation worker, payment, and reward features
- Message queue, outbox, Kafka, or service decomposition

## Documentation Updated

- [x] Related docs updated
- [x] Experiment result added under records/experiments
- [ ] Not needed

```text
AGENTS.md
README.md
docs/01-roadmap.md
docs/03-architecture.md
docs/04-verification-experiments.md
docs/05-workflow-future-scope.md
records/adr/0001-v2-layered-stock-strategy.md
records/experiments/v2-*.md
```

## Checklist

- [x] This PR respects the current version boundary.
- [x] This PR does not implement later-version features early.
- [x] Business logic is explicit and explainable.
- [x] Experiment work is documented if it affects architectural decisions.
- [x] The completed-version tag will be created from the merged `main` commit.
