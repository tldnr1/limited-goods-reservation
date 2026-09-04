from __future__ import annotations

from datetime import timedelta
from hashlib import sha256
import json
import logging
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from limited_goods.clock import utc_now
from limited_goods.config import get_settings
from limited_goods.errors import AppError
from limited_goods.metrics import (
    PURCHASE_OUTCOMES,
    RESERVATIONS,
    observe_purchase,
    observe_purchase_stage,
)
from limited_goods.payments.models import PaymentAttempt
from limited_goods.purchases.models import Order, OrderItem, OrderStatus
from limited_goods.purchases.schemas import (
    OrderItemView,
    OrderView,
    PaymentAttemptView,
    PurchaseRequest,
    ReservationView,
)
from limited_goods.reservations.models import Reservation, ReservationStatus
from limited_goods.sales.models import Inventory, SaleEvent, SaleItem


logger = logging.getLogger(__name__)


def purchase_fingerprint(request: PurchaseRequest) -> str:
    canonical = {
        "sale_event_id": str(request.sale_event_id),
        "items": sorted(
            (
                {"sale_item_id": str(item.sale_item_id), "quantity": item.quantity}
                for item in request.items
            ),
            key=lambda item: item["sale_item_id"],
        ),
    }
    return sha256(json.dumps(canonical, separators=(",", ":")).encode()).hexdigest()


def load_order(session: Session, order_id: UUID, for_update: bool = False) -> Order:
    statement = (
        select(Order)
        .where(Order.id == order_id)
        .execution_options(populate_existing=True)
        .options(
            selectinload(Order.items),
            selectinload(Order.reservation),
            selectinload(Order.payment_attempts),
        )
    )
    if for_update:
        statement = statement.with_for_update()
    order = session.scalar(statement)
    if order is None:
        raise AppError(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.")
    return order


def order_to_view(order: Order) -> OrderView:
    return OrderView(
        id=order.id,
        user_id=order.user_id,
        sale_event_id=order.sale_event_id,
        status=order.status,
        total_amount=order.total_amount,
        items=[
            OrderItemView(
                sale_item_id=item.sale_item_id,
                quantity=item.quantity,
                unit_price=item.unit_price,
            )
            for item in order.items
        ],
        reservation=ReservationView(
            id=order.reservation.id,
            status=order.reservation.status,
            hold_expires_at=order.reservation.hold_expires_at,
            confirmation_deadline=order.reservation.confirmation_deadline,
        ),
        payment_attempts=[
            PaymentAttemptView(
                id=attempt.id,
                status=attempt.status,
                amount=attempt.amount,
                scenario=attempt.scenario,
                provider_reference=attempt.provider_reference,
                started_at=attempt.started_at,
                resolved_at=attempt.resolved_at,
            )
            for attempt in sorted(order.payment_attempts, key=lambda value: value.started_at)
        ],
        created_at=order.created_at,
    )


def get_order_for_user(session: Session, order_id: UUID, user_id: str) -> OrderView:
    order = load_order(session, order_id)
    if order.user_id != user_id:
        raise AppError(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.")
    return order_to_view(order)


@observe_purchase
def create_purchase(
    session: Session,
    user_id: str,
    idempotency_key: str,
    request: PurchaseRequest,
) -> OrderView:
    fingerprint = purchase_fingerprint(request)
    with observe_purchase_stage("idempotency_lookup"):
        existing = session.scalar(
            select(Order).where(
                Order.user_id == user_id, Order.idempotency_key == idempotency_key
            )
        )
    if existing is not None:
        if existing.request_fingerprint != fingerprint:
            raise AppError(
                409,
                "IDEMPOTENCY_KEY_REUSED",
                "같은 멱등키를 다른 구매 요청에 사용할 수 없습니다.",
            )
        view = order_to_view(load_order(session, existing.id))
        PURCHASE_OUTCOMES.labels(outcome="reused").inc()
        return view

    try:
        with observe_purchase_stage("sale_lookup"):
            sale = session.scalar(
                select(SaleEvent).where(SaleEvent.id == request.sale_event_id)
            )
        if sale is None:
            raise AppError(404, "SALE_NOT_FOUND", "판매를 찾을 수 없습니다.")
        now = utc_now()
        if now < sale.opens_at:
            raise AppError(
                409,
                "SALE_NOT_OPEN",
                "판매가 아직 시작되지 않았습니다.",
                {"opens_at": sale.opens_at.isoformat()},
            )

        requested = {item.sale_item_id: item.quantity for item in request.items}
        with observe_purchase_stage("inventory_lock"):
            rows = session.execute(
                select(SaleItem, Inventory)
                .join(Inventory, Inventory.sale_item_id == SaleItem.id)
                .where(SaleItem.id.in_(sorted(requested, key=str)))
                .order_by(SaleItem.id)
                .with_for_update(of=Inventory)
            ).all()
        if len(rows) != len(requested) or any(
            item.sale_event_id != request.sale_event_id for item, _ in rows
        ):
            raise AppError(
                400,
                "INVALID_SALE_ITEMS",
                "요청 상품이 존재하지 않거나 판매에 속하지 않습니다.",
            )

        with observe_purchase_stage("usage_lookup"):
            usage_rows = session.execute(
                select(OrderItem.sale_item_id, func.sum(OrderItem.quantity))
                .join(Order, Order.id == OrderItem.order_id)
                .where(
                    Order.user_id == user_id,
                    Order.status.in_(OrderStatus.COUNTING),
                    OrderItem.sale_item_id.in_(requested),
                )
                .group_by(OrderItem.sale_item_id)
            ).all()
        usage = {sale_item_id: quantity for sale_item_id, quantity in usage_rows}

        failures: list[dict[str, object]] = []
        total_amount = 0
        for item, inventory in rows:
            quantity = requested[item.id]
            used = int(usage.get(item.id, 0))
            if quantity > inventory.available_quantity:
                failures.append(
                    {
                        "sale_item_id": str(item.id),
                        "reason": "INSUFFICIENT_STOCK",
                        "available": inventory.available_quantity,
                    }
                )
            if used + quantity > item.per_user_limit:
                failures.append(
                    {
                        "sale_item_id": str(item.id),
                        "reason": "PER_USER_LIMIT_EXCEEDED",
                        "remaining_limit": max(item.per_user_limit - used, 0),
                    }
                )
            total_amount += item.price * quantity

        if failures:
            RESERVATIONS.labels(outcome="rejected").inc()
            raise AppError(
                409,
                "PURCHASE_REJECTED",
                "모든 상품을 점유할 수 없어 구매 요청 전체를 거절했습니다.",
                {"failures": failures},
            )

        order = Order(
            user_id=user_id,
            sale_event_id=request.sale_event_id,
            status=OrderStatus.PAYMENT_PENDING,
            total_amount=total_amount,
            idempotency_key=idempotency_key,
            request_fingerprint=fingerprint,
        )
        for item, inventory in rows:
            quantity = requested[item.id]
            inventory.available_quantity -= quantity
            inventory.held_quantity += quantity
            order.items.append(
                OrderItem(
                    sale_item_id=item.id,
                    quantity=quantity,
                    unit_price=item.price,
                )
            )
        order.reservation = Reservation(
            status=ReservationStatus.ACTIVE,
            hold_expires_at=now
            + timedelta(seconds=get_settings().reservation_ttl_seconds),
        )
        session.add(order)
        with observe_purchase_stage("commit"):
            session.commit()
    except IntegrityError:
        session.rollback()
        existing = session.scalar(
            select(Order).where(
                Order.user_id == user_id, Order.idempotency_key == idempotency_key
            )
        )
        if existing is not None and existing.request_fingerprint == fingerprint:
            view = order_to_view(load_order(session, existing.id))
            PURCHASE_OUTCOMES.labels(outcome="reused").inc()
            return view
        raise AppError(
            409,
            "CONCURRENT_CONFLICT",
            "동시 요청과 충돌했습니다. 주문 상태를 조회해 주세요.",
        )
    except Exception:
        session.rollback()
        raise

    view = order_to_view(load_order(session, order.id))
    RESERVATIONS.labels(outcome="created").inc()
    PURCHASE_OUTCOMES.labels(outcome="created").inc()
    logger.info(
        "reservation created",
        extra={"event": "reservation_created", "order_id": str(order.id), "user_id": user_id},
    )
    return view
