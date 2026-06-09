# limited-goods-reservation

Spring Boot backend portfolio project for limited goods sale failures.

Core message:

```text
This is not a full commerce project.
This project reproduces and improves limited-sale backend failures step by step.
```

---

## Current Status

The project is in **v0 documentation / project skeleton** stage.

Business features start in v1.

---

## Quick Check

Run the basic test suite:

```text
./gradlew test
```

Run the v0 Docker Compose skeleton:

```text
docker compose up --build
```

Run the k6 smoke scenario through the Compose profile:

```text
docker compose --profile load-test up --build k6
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
