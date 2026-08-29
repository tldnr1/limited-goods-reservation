from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import os


def _int_env(name: str, default: int) -> int:
    return int(os.getenv(name, str(default)))


@dataclass(frozen=True)
class Settings:
    database_url: str
    mock_pg_url: str
    mock_pg_callback_url: str
    mock_pg_secret: str
    reservation_ttl_seconds: int
    payment_grace_seconds: int
    worker_poll_seconds: int
    log_level: str


@lru_cache
def get_settings() -> Settings:
    return Settings(
        database_url=os.getenv(
            "DATABASE_URL",
            "postgresql+psycopg://limited_goods:limited_goods@localhost:5434/limited_goods",
        ),
        mock_pg_url=os.getenv("MOCK_PG_URL", "http://localhost:8080"),
        mock_pg_callback_url=os.getenv(
            "MOCK_PG_CALLBACK_URL",
            "http://localhost:8000/internal/payments/callback",
        ),
        mock_pg_secret=os.getenv("MOCK_PG_SECRET", "local-mock-pg-secret"),
        reservation_ttl_seconds=_int_env("RESERVATION_TTL_SECONDS", 60),
        payment_grace_seconds=_int_env("PAYMENT_GRACE_SECONDS", 10),
        worker_poll_seconds=_int_env("WORKER_POLL_SECONDS", 1),
        log_level=os.getenv("LOG_LEVEL", "INFO"),
    )
