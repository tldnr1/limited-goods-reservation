# 06. Load Test and Observability

This document defines load test and metric strategy.

The purpose of load testing is not to show large numbers. The purpose is to prove version-level failures and improvements.

---

## 1. Tools

```text
k6
Spring Boot Actuator
Micrometer
Docker Compose
```

Optional future tools:

```text
Prometheus
Grafana
```

Initial adoption plan:

```text
v0: Docker-first local experiment skeleton + Spring Boot Actuator / Micrometer endpoints
v1: API container + PostgreSQL container + k6 container, with result summary and database verification queries
v2.1+: Prometheus / Grafana may be added when comparing inventory consistency alternatives
v5+: APM-style tracing can be considered if it supports a specific investigation
```

The official local verification path should run through Docker Compose. Local Java execution is optional and should not be the only way to run tests or experiments.

Windows Docker Desktop / WSL2 results should be treated as reproducible local comparison data, not final production-grade performance numbers. Important benchmark numbers may be re-run on a Linux server or EC2 with the same Docker Compose scenario.

---

## 2. Common k6 Scenario Sizes

Use staged request sizes unless a version requires a different scenario.

```text
v1: stock 100, about 1000 users
v2+: stock 500, about 10000 users
```

Smaller smoke scenarios may be used before the main load scenario.

```text
100 users
500 users
1000 users
```

---

## 3. Common k6 Metrics

```text
requests per second
failed request rate
p50 latency
p95 latency
p99 latency
status code distribution
scenario success count
scenario failure count
```

---

## 4. Business Metrics

Track these metrics where possible:

```text
initial stock
created order count
reserved count
sold count
released count
oversell count
duplicate order count
sold out response count
```

---

## 5. v3+ Metrics

### Waiting Room Metrics

```text
waiting queue size
active token issued count
active token expired count
active token rejected count
reserve request without active token count
```

### Payment Metrics

```text
payment queue backlog
worker throughput
payment success count
payment failure count
payment timeout count
payment unknown count
payment completion latency
retry count
```

---

## 6. Version-Level Measurement Goals

### v1

Goal:

```text
reproduce oversell
```

Expected result:

```text
created successful orders > initial stock
oversell_count > 0
```

---

### v2.2

Goal:

```text
prove Redis Lua reservation prevents oversell
```

Expected result:

```text
oversell_count = 0
sold + reserved <= initial stock
```

---

### v2.4

Goal:

```text
prove oversell=0 under two API instances
```

Expected result:

```text
oversell_count = 0 even with Nginx + API instance 1 + API instance 2
```

---

### v3.1

Goal:

```text
control direct reservation traffic
```

Expected result:

```text
reserve requests without active token are rejected
reservation API request volume is controlled
waiting queue and active token metrics are visible
```

---

### v3.2

Goal:

```text
separate PG delay from API request thread
```

Expected result:

```text
API response time is not directly tied to Mock PG delay
payment queue backlog is visible
worker processes payment asynchronously
```

---

## 7. Scenario Documentation Format

Each k6 scenario should include:

```text
version
purpose
system setup
initial data
request pattern
expected failure or success
metrics captured
result summary
```

Example file path:

```text
k6/v1/oversell-baseline.js
k6/v2-2/redis-lua-reservation.js
k6/v2-4/api-scaleout-consistency.js
k6/v3-1/waiting-room.js
k6/v3-2/payment-worker-delay.js
```

---

## 8. Result Documentation

Store result summaries under:

```text
docs/experiments/
```

or version-specific result documents.

Suggested format:

```text
# Experiment Title

## Version

## Purpose

## Setup

## Alternatives Compared

## Metrics

## Result

## Decision

## Limitations
```
