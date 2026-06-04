# limited-goods-reservation docs package

This package contains initial v0 documentation for Codex.

Core message:

```text
This is not a full commerce project.
This is a Spring Boot backend portfolio project that reproduces and improves limited-sale backend failures step by step.
```

## Local Experiment Direction

The intended local workflow is Docker-first. Spring Boot API instances, PostgreSQL, Redis, RabbitMQ, Nginx, k6, and observability components should be introduced as Docker Compose services when their versions allow them.

Local Java can be used as a developer convenience, but the official project verification path should be reproducible through Docker Compose.

Recommended reading order:

```text
1. AGENTS.md
2. docs/00-project-charter.md
3. docs/01-version-roadmap.md
4. docs/02-domain-model.md
5. docs/03-architecture.md
6. docs/04-data-model.md
7. docs/05-testing-strategy.md
8. docs/06-load-test-and-observability.md
9. docs/07-github-workflow.md
10. docs/08-experiment-policy.md
11. docs/09-future-scope.md
12. docs/experiments/README.md
13. .github/pull_request_template.md
```
