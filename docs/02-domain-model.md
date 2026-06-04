# 02. Domain Model

This document defines the simplified domain model used until v3.2.

---

## 1. Domain Scope

This project models limited goods sale with product-level single stock.

Until v3.2, the following simplifications are intentional:

```text
product-level stock only
no SKU
no size/color option
one user can buy only one unit of one product
no cart
no shipping
no coupon
X-USER-ID based test identity
```

---

## 2. Limited Goods Flow

Limited goods are handled by reservation before payment.

```text
waiting queue
→ active token
→ reservation
→ payment
→ sold or released
```

Full target flow:

```text
1. User enters waiting queue.
2. Admission Scheduler issues active token.
3. User calls reservation API with active token.
4. Redis Lua checks and reserves stock atomically.
5. Reservation is created with TTL.
6. Order is created as PENDING_PAYMENT.
7. Payment is requested.
8. Payment Worker processes Mock PG result.
9. Success confirms reservation and marks order as PAID.
10. Failure or expiration releases reservation.
```

---

## 3. Active Token

Definition:

```text
Active token is a temporary right to attempt reservation.
```

Important:

```text
Active token is not a purchase guarantee.
```

Properties:

```text
productId based
userId based
TTL based
issued by Admission Scheduler
required before reservation API in v3.1+
```

Suggested Redis key:

```text
active-token:{productId}:{userId}
```

---

## 4. Reservation

Definition:

```text
Reservation is a temporary stock hold.
```

Reservation starts only when Redis Lua reservation succeeds.

Reservation is responsible for:

```text
preventing oversell
holding limited stock for a short time
connecting order and payment
being released after failure or expiration
```

Suggested Redis keys:

```text
stock:available:{productId}
reservation:{reservationId}
user:reservation:{productId}:{userId}
```

Default TTL policy:

```text
production-like default: 3 minutes
test override: 5-10 seconds
```

---

## 5. Payment

Payment is introduced in v3.2.

Payment is processed asynchronously through RabbitMQ + Payment Worker.

Mock PG MVP scenarios:

```text
success
fail
delay
timeout
```

Timeout rule:

```text
Timeout is not confirmed failure.
Timeout should be treated as UNKNOWN or retry target.
```

---

## 6. Reward

Reward is introduced in v4.

Reward is different from Limited stock.

```text
Limited:
product stock itself is limited
reservation before payment is required

Reward:
product purchase itself can succeed
limited reward is allocated after payment success
```

MVP reward policy:

```text
payment success processing order based allocation
```

Improvement candidate:

```text
PG approved_at based allocation
```

---

## 7. Status Model

### v1

```text
OrderStatus only
```

### v2+

```text
ReservationStatus added
```

### v3+

```text
PaymentStatus added
```

### v4+

```text
RewardStatus added
```

---

## 8. Status Candidates

### ReservationStatus

```text
RESERVED
CONFIRMED
EXPIRED
CANCELED
RELEASED
```

### OrderStatus

```text
PENDING_PAYMENT
PAID
FAILED
CANCELED
```

### PaymentStatus

```text
READY
PROCESSING
SUCCESS
FAILED
TIMEOUT
UNKNOWN
```

### RewardStatus

```text
GRANTED
NOT_GRANTED
```

---

## 9. Status Transition Examples

### Reservation

```text
RESERVED
→ payment success
→ CONFIRMED
```

```text
RESERVED
→ TTL expired
→ EXPIRED / RELEASED
```

```text
RESERVED
→ payment failed
→ RELEASED
```

### Order

```text
PENDING_PAYMENT
→ payment success
→ PAID
```

```text
PENDING_PAYMENT
→ payment failed
→ FAILED
```

### Payment

```text
READY
→ worker starts
→ PROCESSING
```

```text
PROCESSING
→ PG success
→ SUCCESS
```

```text
PROCESSING
→ PG fail
→ FAILED
```

```text
PROCESSING
→ PG timeout
→ UNKNOWN
```

```text
UNKNOWN
→ retry success
→ SUCCESS
```

---

## 10. One User One Product Rule

Until v3.2:

```text
userId + productId can have only one active reservation
userId + productId can have only one paid order
```

Behavior:

```text
same idempotency key:
return previous result

active reservation already exists:
409 Already Reserved

paid order already exists:
409 Already Purchased
```
