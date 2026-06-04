# 09. Future Scope

This document records intentionally excluded items and possible future improvements.

---

## 1. Why This Exists

The project intentionally avoids becoming a full e-commerce service during v1-v4.

Future scope is recorded so that excluded ideas are not forgotten, but they should not leak into early versions.

---

## 2. Commerce Domain Expansion

Future candidates:

```text
SKU
size/color option
multi-quantity order
cart
shipping address
coupon
saleEvent model
```

Current decision:

```text
Out of scope until v5+.
```

Reason:

```text
The current goal is to isolate limited-sale concurrency and consistency problems with product-level single stock.
```

---

## 3. Authentication and Security

Future candidates:

```text
real signup/login
JWT
role-based admin API
refresh token
rate limit by authenticated user
```

Current decision:

```text
Use X-USER-ID for v1-v4.
```

Reason:

```text
Authentication is not the main portfolio target of this project.
```

---

## 4. Payment Recovery

Future candidates:

```text
duplicate webhook handling
delayed webhook handling
webhook_events table
DLQ
outbox pattern
reconciliation worker
manual review state
```

Current decision:

```text
v3.2 MVP handles success/fail/delay/timeout only.
```

Reason:

```text
v3.2 focuses on separating PG delay from API request threads.
```

---

## 5. Messaging and Event Architecture

Future candidates:

```text
Kafka
event sourcing
outbox relay
consumer group tuning
```

Current decision:

```text
RabbitMQ is enough for v3.2 payment worker.
Kafka is out of scope until real bottleneck or event-stream need appears.
```

---

## 6. Redis Scaling

Future candidates:

```text
Redis Cluster
Redis Sentinel
key partitioning strategy
hot key mitigation
```

Current decision:

```text
single Redis instance in Docker Compose is enough for v1-v4.
```

Reason:

```text
The project first needs to prove correctness and traffic-control behavior before distributed Redis operation.
```

---

## 7. Deployment and Operations

Future candidates:

```text
AWS EC2 deployment
RDS
ElastiCache
Amazon MQ
Kubernetes
Prometheus
Grafana
alerting
```

Current decision:

```text
Docker Compose reproducibility is required.
Cloud deployment is optional.
Kubernetes is out of scope until v5+.
```

---

## 8. AI Extension Possibility

Future candidates:

```text
abnormal purchase attempt detection
bot-like behavior scoring
LLM-based admin incident summary
fraud-risk explanation
traffic anomaly report generation
```

Current decision:

```text
AI extension is future scope after backend core is complete.
```

Reason:

```text
The current project should first prove backend fundamentals: concurrency, consistency, traffic control, and async processing.
```
