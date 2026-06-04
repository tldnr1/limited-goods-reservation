# 00. Project Charter

## 1. Project Name

```text
limited-goods-reservation
```

---

## 2. Project Purpose

This project is a Spring Boot backend portfolio project for a limited goods sale system.

The purpose is not to build a complete e-commerce service. The purpose is to reproduce, analyze, and improve backend failures that happen in limited sales.

Core problems:

```text
oversell
inventory consistency under concurrency
traffic surge before reservation
external payment delay
reward allocation policy
```

Core project narrative:

```text
failure reproduction
→ root cause analysis
→ alternative comparison
→ structure selection
→ metric-based validation
→ next version improvement
```

---

## 3. Portfolio Message

This project should show that the developer can:

```text
reproduce a backend failure with a controlled scenario
explain why the failure happens
compare multiple architectural alternatives
choose a structure based on the problem characteristics
validate the improvement with tests and metrics
keep version boundaries clear
```

The project is not about using many technologies. It is about explaining why each structure is introduced at each step.

---

## 4. Main Domain

The main domain is Limited goods sale.

Limited goods sale is modeled as:

```text
reservation before payment
```

A user does not immediately buy the product. Instead:

```text
user obtains a chance to enter
→ user attempts reservation
→ stock is temporarily reserved
→ payment is processed
→ stock becomes sold after payment success
→ reservation is released after failure or expiration
```

---

## 5. Important Domain Distinctions

### Active Token

```text
Active token is a temporary right to attempt reservation.
```

It is not a purchase guarantee.

### Reservation

```text
Reservation is a temporary stock hold created by Redis Lua reservation.
```

Reservation is the first point where actual limited stock is occupied.

### Payment

```text
Payment confirms or releases the reservation.
```

Payment success makes the order paid and the reservation confirmed.

Payment failure or expiration releases the reservation.

---

## 6. Scope Until v3.2

Until v3.2, the project uses a deliberately simplified domain.

```text
product-level single stock
one user can buy one unit of one product
productId-based waiting queue
productId-based active token
productId-based reservation
X-USER-ID based test identity
Mock PG
```

---

## 7. Non-Goals Until v3.2

The following are intentionally excluded until v3.2:

```text
SKU
size/color option
cart
multi-quantity order
shipping
coupon
real authentication / JWT
real PG integration
saleEvent model
webhook recovery
duplicate webhook
delayed webhook
DLQ
outbox pattern
reconciliation worker
Kafka
Redis Cluster
Kubernetes
```

These are not ignored because they are unimportant. They are excluded because the current project goal is to make the concurrency, consistency, traffic control, and payment delay problems visible and measurable.

---

## 8. Required Technical Stack

```text
Java 17
Spring Boot 3.x
Gradle
PostgreSQL
JPA
Redis
Redis Lua Script
RabbitMQ
Docker Compose
JUnit5
AssertJ
Testcontainers
k6
Actuator
Micrometer
```

---

## 9. Project Success Criteria

The project is successful if the repository clearly shows:

```text
v1: oversell can be reproduced
v2.2: oversell=0 is achieved with Redis Lua reservation
v2.4: oversell=0 is maintained under API scale-out
v3.1: direct reservation access is controlled by Waiting Room + Active Token
v3.2: external payment delay is separated from API request threads
v4: reward allocation policy trade-off is documented and implemented minimally
```

---

## 10. Local Experiment Environment

The project uses a Docker-first local experiment workflow.

```text
Spring Boot API instances should run as Docker Compose services for version-level verification.
PostgreSQL, Redis, RabbitMQ, Nginx, k6, and observability components should be added to Docker Compose when their versions allow them.
Local Java execution is allowed as a convenience, but it should not be the only official verification path.
```

This keeps local experiments close to the later scale-out narrative:

```text
single API container
→ API containers behind Nginx
→ shared Redis / PostgreSQL
→ k6 load scenarios in the same explicit topology
```

---

## 11. Related Documents

```text
docs/01-version-roadmap.md
docs/02-domain-model.md
docs/03-architecture.md
docs/04-data-model.md
docs/05-testing-strategy.md
docs/06-load-test-and-observability.md
docs/07-github-workflow.md
docs/08-experiment-policy.md
docs/09-future-scope.md
```
