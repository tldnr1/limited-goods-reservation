# Experiments README

This directory stores comparison results that support architectural decisions.

Experiment documents are more important than keeping every experiment implementation in main.

---

## 1. Purpose

Use experiments to show:

```text
what alternatives were considered
what criteria were used
what was measured or reasoned about
why the main path was selected
```

---

## 2. Standard Format

Use this format for each experiment document:

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

---

## 3. Planned Experiment Documents

```text
v2-1-inventory-consistency.md
v3-1-entry-control.md
v3-2-payment-processing.md
v4-reward-allocation.md
```

---

## 4. Branch Policy

Experiment branches may be named like:

```text
experiment/v2-1-rdb-atomic-update
experiment/v2-1-rdb-pessimistic-lock
experiment/v2-1-redis-distributed-lock
experiment/v3-1-rate-limit
experiment/v3-2-sync-payment
experiment/v3-2-background-task
```

They do not have to be merged into main.

They may be kept as saved implementation records when the measured result or code shape is useful for later explanation.

---

## 5. Experiment Quality Rule

Experiment code may be minimal.

But experiment documentation should be clear enough to explain the decision in an interview or README.
