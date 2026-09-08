## Version

- [ ] v0
- [x] v1
- [ ] v2
- [ ] v3.1
- [ ] v3.2
- [ ] v4
- [ ] v5+

## Base / Release

Target branch: `main`

Tag after merge: `v1`

## Purpose

Reproduce oversell and stock/order inconsistency with a deliberately simple purchase flow before introducing concurrency controls.

## Changes

- Add the product, product stock, order, and purchase API layers.
- Implement the naive RDB read-check-write stock flow.
- Add the k6 oversell scenario and database verification query.

## Tests

- [x] Unit test
- [x] Integration test
- [ ] Testcontainers
- [x] k6 load test
- [x] Manual test
- [ ] Not needed

## Metrics / Result

```text
initial stock: 100
request count: 1000
successful purchases: 973
sold out responses: 27
DB sold quantity: 97
DB order count: 973
oversell_count: 873
order_stock_gap: 876
```

## Not Included

- Redis or stock strategy abstractions
- Waiting room, payment, reward, and future-version features

## Documentation Updated

- [x] Related docs updated
- [x] Experiment result added under records/experiments
- [ ] Not needed

```text
AGENTS.md
README.md
docs/01-roadmap.md
docs/02-domain-data.md
docs/04-verification-experiments.md
records/experiments/v1-oversell-baseline.md
```

## Checklist

- [x] This PR respects the current version boundary.
- [x] This PR does not implement later-version features early.
- [x] Business logic is explicit and explainable.
- [x] Experiment work is documented if it affects architectural decisions.
- [x] The completed-version tag will be created from the merged `main` commit.
