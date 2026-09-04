from collections.abc import Iterator
from contextlib import contextmanager
from functools import wraps
from time import perf_counter

from prometheus_client import Counter, Gauge, Histogram

from limited_goods.errors import AppError


HTTP_REQUESTS = Counter(
    "limited_goods_http_requests_total",
    "HTTP requests",
    ("method", "path", "status"),
)
HTTP_DURATION = Histogram(
    "limited_goods_http_request_duration_seconds",
    "HTTP request duration",
    ("method", "path"),
)
PURCHASE_OUTCOMES = Counter(
    "limited_goods_purchase_outcomes_total",
    "Purchase request outcomes",
    ("outcome",),
)
PURCHASE_DURATION = Histogram(
    "limited_goods_purchase_duration_seconds",
    "Purchase service duration",
)
PURCHASE_STAGE_DURATION = Histogram(
    "limited_goods_purchase_stage_duration_seconds",
    "Purchase service duration by bounded stage",
    ("stage",),
)
DB_POOL_CONNECTIONS = Gauge(
    "limited_goods_db_pool_connections",
    "SQLAlchemy connection pool state",
    ("state",),
)
DB_POOL_CAPACITY = Gauge(
    "limited_goods_db_pool_capacity",
    "Maximum SQLAlchemy connection pool capacity",
)
RESERVATIONS = Counter(
    "limited_goods_reservations_total",
    "Reservation outcomes",
    ("outcome",),
)
PAYMENT_RESULTS = Counter(
    "limited_goods_payment_results_total",
    "Payment result outcomes",
    ("result", "duplicate"),
)
RECONCILIATIONS = Counter(
    "limited_goods_payment_reconciliations_total",
    "Payment reconciliation outcomes",
    ("result",),
)
INVARIANT_FAILURES = Counter(
    "limited_goods_invariant_failures_total",
    "Detected invariant failures",
    ("invariant",),
)


@contextmanager
def observe_purchase_stage(stage: str) -> Iterator[None]:
    started = perf_counter()
    try:
        yield
    finally:
        PURCHASE_STAGE_DURATION.labels(stage=stage).observe(perf_counter() - started)


def observe_purchase(function):
    @wraps(function)
    def measured(*args, **kwargs):
        started = perf_counter()
        try:
            return function(*args, **kwargs)
        except AppError as error:
            outcome = (
                "conflict"
                if error.code in {"IDEMPOTENCY_KEY_REUSED", "CONCURRENT_CONFLICT"}
                else "rejected"
            )
            PURCHASE_OUTCOMES.labels(outcome=outcome).inc()
            raise
        except Exception:
            PURCHASE_OUTCOMES.labels(outcome="error").inc()
            raise
        finally:
            PURCHASE_DURATION.observe(perf_counter() - started)

    return measured
