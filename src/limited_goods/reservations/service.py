from __future__ import annotations

import logging
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from limited_goods.clock import utc_now
from limited_goods.errors import AppError
from limited_goods.metrics import INVARIANT_FAILURES, RESERVATIONS
from limited_goods.payments.models import PaymentAttempt, PaymentAttemptStatus
from limited_goods.purchases.models import Order, OrderItem, OrderStatus
from limited_goods.reservations.models import Reservation, ReservationStatus
from limited_goods.sales.models import Inventory


logger = logging.getLogger(__name__)


def _locked_inventory_for_order(
    session: Session, order_id: UUID
) -> list[tuple[OrderItem, Inventory]]:
    return session.execute(
        select(OrderItem, Inventory)
        .join(Inventory, Inventory.sale_item_id == OrderItem.sale_item_id)
        .where(OrderItem.order_id == order_id)
        .order_by(OrderItem.sale_item_id)
        .with_for_update(of=Inventory)
    ).all()


def expire_reservation_locked(
    session: Session, order: Order, reservation: Reservation
) -> bool:
    if reservation.status != ReservationStatus.ACTIVE:
        return False
    rows = _locked_inventory_for_order(session, order.id)
    for order_item, inventory in rows:
        if inventory.held_quantity < order_item.quantity:
            INVARIANT_FAILURES.labels(invariant="held_quantity_sufficient").inc()
            raise AppError(
                500,
                "INVENTORY_INVARIANT_VIOLATION",
                "점유 재고 수량이 주문 수량보다 적습니다.",
            )
    for order_item, inventory in rows:
        inventory.held_quantity -= order_item.quantity
        inventory.available_quantity += order_item.quantity
    reservation.status = ReservationStatus.EXPIRED
    order.status = OrderStatus.EXPIRED
    return True


def confirm_reservation_locked(
    session: Session, order: Order, reservation: Reservation
) -> bool:
    if reservation.status == ReservationStatus.CONFIRMED:
        return False
    if reservation.status != ReservationStatus.ACTIVE:
        raise AppError(
            409,
            "RESERVATION_NOT_ACTIVE",
            "활성 상태가 아닌 점유는 구매 확정할 수 없습니다.",
        )
    rows = _locked_inventory_for_order(session, order.id)
    for order_item, inventory in rows:
        if inventory.held_quantity < order_item.quantity:
            INVARIANT_FAILURES.labels(invariant="held_quantity_sufficient").inc()
            raise AppError(
                500,
                "INVENTORY_INVARIANT_VIOLATION",
                "점유 재고 수량이 주문 수량보다 적습니다.",
            )
    for order_item, inventory in rows:
        inventory.held_quantity -= order_item.quantity
        inventory.sold_quantity += order_item.quantity
    reservation.status = ReservationStatus.CONFIRMED
    order.status = OrderStatus.CONFIRMED
    return True


def expire_due_reservations(session: Session) -> int:
    now = utc_now()
    reservation_ids = list(
        session.scalars(
            select(Reservation.id).where(
                Reservation.status == ReservationStatus.ACTIVE,
                Reservation.hold_expires_at <= now,
            )
        )
    )
    expired_count = 0
    session.rollback()

    for reservation_id in reservation_ids:
        try:
            reservation = session.scalar(
                select(Reservation)
                .where(Reservation.id == reservation_id)
                .with_for_update(skip_locked=True)
            )
            if reservation is None or reservation.status != ReservationStatus.ACTIVE:
                session.rollback()
                continue
            order = session.scalar(
                select(Order).where(Order.id == reservation.order_id).with_for_update()
            )
            blocking_attempt = session.scalar(
                select(PaymentAttempt).where(
                    PaymentAttempt.order_id == order.id,
                    PaymentAttempt.status.in_(PaymentAttemptStatus.BLOCKING),
                )
            )
            if blocking_attempt is not None:
                session.rollback()
                continue
            if expire_reservation_locked(session, order, reservation):
                expired_count += 1
                RESERVATIONS.labels(outcome="expired").inc()
                logger.info(
                    "reservation expired",
                    extra={"event": "reservation_expired", "order_id": str(order.id)},
                )
            session.commit()
        except Exception:
            session.rollback()
            logger.exception(
                "failed to expire reservation",
                extra={"event": "reservation_expiry_failed"},
            )
    return expired_count
