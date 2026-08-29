from prometheus_client import Counter, Histogram


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
