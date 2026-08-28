# Project

## Purpose

Limited Goods will implement the core purchase flow for limited streamer merchandise: a buyer secures scarce inventory and completes payment.

The architecture goal is to verify inventory and payment consistency, performance, and security within limited cloud cost and compute resources, then select architecture and technology from measured bottlenecks.

## Starting Point

- This `main` starts from a new Git history and has no application code yet.
- The service will be Python-based, but its framework and execution model are not decided.
- The previous Java/Spring project is preserved in `archive/java-spring-v3.2`.
- Previous RDB atomic update, Redis Lua, waiting-room, reservation, idempotency, compensation, and front-gate results remain reference evidence rather than implementation requirements.
- `README.md`, `AGENTS.md`, `PROJECT.md`, and `DESIGN.md` are the initial project documents.

## Questions to Resolve

The new project must define these before implementation scope is committed:

- the exact buyer journey and external actors
- inventory, reservation, order, and payment states and invariants
- reservation expiry and inventory release behavior
- retry and idempotency boundaries
- payment failure, timeout, and recovery behavior
- API and identity boundaries
- security and abuse assumptions
- measurable performance and cost targets
- the initial cloud and local verification environments

No broader commerce scope is implied until these questions are resolved.
