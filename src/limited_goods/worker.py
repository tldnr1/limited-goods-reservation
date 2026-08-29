from __future__ import annotations

import logging
import time

from limited_goods.config import get_settings
from limited_goods.db import SessionLocal, import_models
from limited_goods.logging import configure_logging
from limited_goods.payments.provider import MockPgClient
from limited_goods.payments.service import reconcile_due_payments
from limited_goods.reservations.service import expire_due_reservations


configure_logging()
import_models()
logger = logging.getLogger(__name__)


def run_once() -> tuple[int, int]:
    with SessionLocal() as session:
        expired = expire_due_reservations(session)
    with SessionLocal() as session:
        reconciled = reconcile_due_payments(session, MockPgClient())
    return expired, reconciled


def main() -> None:
    interval = get_settings().worker_poll_seconds
    logger.info("worker started", extra={"event": "worker_started"})
    while True:
        try:
            expired, reconciled = run_once()
            if expired or reconciled:
                logger.info(
                    "worker cycle completed",
                    extra={"event": "worker_cycle"},
                )
        except Exception:
            logger.exception("worker cycle failed", extra={"event": "worker_cycle_failed"})
        time.sleep(interval)


if __name__ == "__main__":
    main()
