# Design

## Status

This document records the initialization direction. It is not yet an architecture specification.

## Initial Principles

- Business contract before framework and infrastructure choices.
- Correctness boundaries before optimization.
- Cost-aware design for limited cloud and compute resources.
- Evidence-driven choices based on reproducible measurements.
- Minimal implementation until the core purchase flow is explicit.
- Historical experiments may inform decisions, but do not determine the new structure.

## Undecided Areas

- Python framework and dependency set
- FastAPI or another API boundary
- synchronous versus asynchronous request handling
- persistence model and durable source of truth
- cache, queue, and background-worker needs
- deployment topology and cloud services
- observability and load-test targets
- security controls and operational failure handling

These choices should be made only after the project defines its states, invariants, failure cases, and measurable constraints.

## Historical Evidence

The archived project contains measured comparisons for RDB atomic updates, Redis Lua stock decisions, waiting-room admission, reservation consistency, idempotency, compensation, and a Redis front gate. Reference that evidence when the new business contract raises the same decision question; do not copy the old implementation by default.
