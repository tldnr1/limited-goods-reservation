from __future__ import annotations

from datetime import timedelta
from hashlib import sha256
import json
import logging
from uuid import UUID

import httpx
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from limited_goods.clock import utc_now
from limited_goods.config import get_settings
from limited_goods.errors import AppError
from limited_goods.metrics import PAYMENT_RESULTS, RECONCILIATIONS
from limited_goods.payments.models import PaymentAttempt, PaymentAttemptStatus
from limited_goods.payments.provider import PaymentProvider
from limited_goods.payments.schemas import (
    PaymentCallback,
    PaymentCallbackResult,
    PaymentStartRequest,
)
from limited_goods.purchases.models import Order, OrderStatus
from limited_goods.purchases.schemas import OrderView
from limited_goods.purchases.service import load_order, order_to_view
from limited_goods.reservations.models import Reservation, ReservationStatus
from limited_goods.reservations.service import (
    confirm_reservation_locked,
    expire_reservation_locked,
)


logger = logging.getLogger(__name__)


def payment_fingerprint(request: PaymentStartRequest) -> str:
    return sha256(
        json.dumps(request.model_dump(), sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def start_payment(
    session: Session,
    provider: PaymentProvider,
    order_id: UUID,
    user_id: str,
    idempotency_key: str,
    request: PaymentStartRequest,
) -> OrderView:
    fingerprint = payment_fingerprint(request)
    existing = session.scalar(
        select(PaymentAttempt).where(
            PaymentAttempt.order_id == order_id,
            PaymentAttempt.idempotency_key == idempotency_key,
        )
    )
    if existing is not None:
        if existing.request_fingerprint != fingerprint:
            raise AppError(
                409,
                "IDEMPOTENCY_KEY_REUSED",
                "같은 멱등키를 다른 결제 요청에 사용할 수 없습니다.",
            )
        return order_to_view(load_order(session, order_id))

    try:
        order = session.scalar(
            select(Order).where(Order.id == order_id).with_for_update()
        )
        if order is None or order.user_id != user_id:
            raise AppError(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.")
        reservation = session.scalar(
            select(Reservation)
            .where(Reservation.order_id == order.id)
            .with_for_update()
        )
        if order.status == OrderStatus.CONFIRMED:
            raise AppError(409, "ORDER_CONFIRMED", "이미 구매 확정된 주문입니다.")
        if reservation.status != ReservationStatus.ACTIVE:
            raise AppError(409, "RESERVATION_NOT_ACTIVE", "점유가 활성 상태가 아닙니다.")

        blocking = session.scalar(
            select(PaymentAttempt).where(
                PaymentAttempt.order_id == order.id,
                PaymentAttempt.status.in_(PaymentAttemptStatus.BLOCKING),
            )
        )
        if blocking is not None:
            raise AppError(
                409,
                "PAYMENT_ATTEMPT_BLOCKED",
                "진행 중이거나 결과 미확정인 결제가 있습니다.",
                {"payment_attempt_id": str(blocking.id), "status": blocking.status},
            )

        now = utc_now()
        if now >= reservation.hold_expires_at:
            expire_reservation_locked(session, order, reservation)
            session.commit()
            raise AppError(409, "RESERVATION_EXPIRED", "점유 시간이 만료되었습니다.")

        attempt = PaymentAttempt(
            order_id=order.id,
            status=PaymentAttemptStatus.PROCESSING,
            amount=order.total_amount,
            idempotency_key=idempotency_key,
            request_fingerprint=fingerprint,
            scenario=request.scenario,
        )
        session.add(attempt)
        order.status = OrderStatus.PAYMENT_PROCESSING
        reservation.confirmation_deadline = reservation.hold_expires_at + timedelta(
            seconds=get_settings().payment_grace_seconds
        )
        session.commit()
    except IntegrityError as error:
        session.rollback()
        raise AppError(
            409,
            "CONCURRENT_PAYMENT_CONFLICT",
            "다른 결제 시도와 충돌했습니다. 주문 상태를 조회해 주세요.",
        ) from error
    except AppError:
        if session.in_transaction():
            session.rollback()
        raise
    except Exception:
        session.rollback()
        raise

    try:
        provider_status = provider.create_payment(attempt.id, attempt.amount, request)
        attempt = session.get(PaymentAttempt, attempt.id)
        attempt.provider_reference = provider_status.provider_reference
        attempt.provider_payload = provider_status.payload
        if provider_status.status == PaymentAttemptStatus.UNKNOWN:
            attempt.status = PaymentAttemptStatus.UNKNOWN
        session.commit()
    except (httpx.HTTPError, OSError) as error:
        session.rollback()
        attempt = session.get(PaymentAttempt, attempt.id)
        attempt.status = PaymentAttemptStatus.UNKNOWN
        attempt.provider_payload = {"error": type(error).__name__}
        session.commit()
        logger.exception(
            "payment provider outcome unknown",
            extra={
                "event": "payment_provider_unknown",
                "order_id": str(order_id),
                "payment_attempt_id": str(attempt.id),
            },
        )

    return order_to_view(load_order(session, order_id))


def apply_payment_result(
    session: Session, callback: PaymentCallback
) -> PaymentCallbackResult:
    try:
        attempt = session.scalar(
            select(PaymentAttempt)
            .where(PaymentAttempt.id == callback.payment_attempt_id)
            .with_for_update()
        )
        if attempt is None:
            raise AppError(404, "PAYMENT_ATTEMPT_NOT_FOUND", "결제 시도를 찾을 수 없습니다.")
        if (
            attempt.provider_reference is not None
            and attempt.provider_reference != callback.provider_reference
        ):
            raise AppError(
                409, "PROVIDER_REFERENCE_MISMATCH", "결제사 참조값이 일치하지 않습니다."
            )

        if attempt.status == callback.result:
            PAYMENT_RESULTS.labels(result=callback.result, duplicate="true").inc()
            session.commit()
            return PaymentCallbackResult(applied=False, duplicate=True)
        if attempt.status in (
            PaymentAttemptStatus.SUCCEEDED,
            PaymentAttemptStatus.FAILED,
        ):
            raise AppError(
                409,
                "CONFLICTING_PAYMENT_RESULT",
                "이미 확정된 결제 결과와 다른 결과를 적용할 수 없습니다.",
            )

        order = session.scalar(
            select(Order).where(Order.id == attempt.order_id).with_for_update()
        )
        reservation = session.scalar(
            select(Reservation)
            .where(Reservation.order_id == order.id)
            .with_for_update()
        )
        now = utc_now()
        attempt.provider_reference = callback.provider_reference
        attempt.resolved_at = now

        if callback.result == PaymentAttemptStatus.SUCCEEDED:
            changed = confirm_reservation_locked(session, order, reservation)
            attempt.status = PaymentAttemptStatus.SUCCEEDED
            duplicate = not changed
        else:
            attempt.status = PaymentAttemptStatus.FAILED
            if now >= reservation.hold_expires_at:
                expire_reservation_locked(session, order, reservation)
            elif order.status != OrderStatus.CONFIRMED:
                order.status = OrderStatus.PAYMENT_PENDING
            duplicate = False
        session.commit()
    except Exception:
        session.rollback()
        raise

    PAYMENT_RESULTS.labels(result=callback.result, duplicate=str(duplicate).lower()).inc()
    logger.info(
        "payment result applied",
        extra={
            "event": "payment_result_applied",
            "order_id": str(order.id),
            "payment_attempt_id": str(attempt.id),
        },
    )
    return PaymentCallbackResult(applied=not duplicate, duplicate=duplicate)


def reconcile_due_payments(session: Session, provider: PaymentProvider) -> int:
    now = utc_now()
    attempts = list(
        session.scalars(
            select(PaymentAttempt)
            .join(Reservation, Reservation.order_id == PaymentAttempt.order_id)
            .where(
                PaymentAttempt.status.in_(PaymentAttemptStatus.BLOCKING),
                Reservation.status == ReservationStatus.ACTIVE,
                Reservation.confirmation_deadline.is_not(None),
                Reservation.confirmation_deadline <= now,
                PaymentAttempt.provider_reference.is_not(None),
            )
        )
    )
    session.rollback()
    reconciled = 0
    for attempt in attempts:
        try:
            status = provider.get_status(attempt.provider_reference)
            if status.status in (
                PaymentAttemptStatus.SUCCEEDED,
                PaymentAttemptStatus.FAILED,
            ):
                apply_payment_result(
                    session,
                    PaymentCallback(
                        payment_attempt_id=attempt.id,
                        provider_reference=attempt.provider_reference,
                        result=status.status,
                    ),
                )
            else:
                locked_attempt = session.scalar(
                    select(PaymentAttempt)
                    .where(PaymentAttempt.id == attempt.id)
                    .with_for_update()
                )
                if locked_attempt.status in PaymentAttemptStatus.BLOCKING:
                    locked_attempt.status = PaymentAttemptStatus.UNKNOWN
                    locked_attempt.provider_payload = status.payload
                    session.commit()
            RECONCILIATIONS.labels(result=status.status).inc()
            reconciled += 1
        except Exception:
            session.rollback()
            logger.exception(
                "payment reconciliation failed",
                extra={
                    "event": "payment_reconciliation_failed",
                    "payment_attempt_id": str(attempt.id),
                },
            )
    return reconciled
