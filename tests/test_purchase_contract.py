from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from sqlalchemy import select

from limited_goods.clock import utc_now
from limited_goods.db import SessionLocal
from limited_goods.errors import AppError
from limited_goods.purchases.models import Order
from limited_goods.purchases.schemas import PurchaseItemRequest, PurchaseRequest
from limited_goods.purchases.service import create_purchase
from limited_goods.reservations.models import Reservation
from limited_goods.reservations.service import expire_due_reservations
from limited_goods.sales.schemas import SaleCreate, SaleItemCreate
from limited_goods.sales.service import create_sale, get_sale
from tests.helpers import create_open_sale


def purchase_request(sale, quantities: tuple[int, ...]) -> PurchaseRequest:
    return PurchaseRequest(
        sale_event_id=sale.id,
        items=[
            PurchaseItemRequest(sale_item_id=item.id, quantity=quantity)
            for item, quantity in zip(sale.items, quantities, strict=True)
        ],
    )


def test_preopen_sale_is_browsable_but_not_purchasable(session):
    sale = create_sale(
        session,
        SaleCreate(
            name="예정 판매",
            opens_at=utc_now() + timedelta(minutes=10),
            items=[
                SaleItemCreate(
                    name="상품", price=10_000, total_quantity=2, per_user_limit=1
                )
            ],
        ),
    )

    assert get_sale(session, sale.id).items[0].available_quantity == 2
    with pytest.raises(AppError, match="판매가 아직") as error:
        create_purchase(session, "buyer-1", "purchase-1", purchase_request(sale, (1,)))
    assert error.value.code == "SALE_NOT_OPEN"


def test_multi_item_purchase_is_all_or_nothing(session):
    sale = create_open_sale(session, quantities=(2, 1), limits=(2, 1))
    create_purchase(session, "other", "take-last", purchase_request(sale, (1, 1)))

    before = get_sale(session, sale.id)
    with pytest.raises(AppError) as error:
        create_purchase(session, "buyer", "all-or-nothing", purchase_request(sale, (1, 1)))

    after = get_sale(session, sale.id)
    assert error.value.code == "PURCHASE_REJECTED"
    assert after.items[0].available_quantity == before.items[0].available_quantity
    assert after.items[0].held_quantity == before.items[0].held_quantity


def test_purchase_idempotency_returns_same_order_and_rejects_different_payload(session):
    sale = create_open_sale(session, quantities=(3,), limits=(3,))
    request = purchase_request(sale, (1,))

    first = create_purchase(session, "buyer", "same-key", request)
    second = create_purchase(session, "buyer", "same-key", request)
    assert first.id == second.id

    with pytest.raises(AppError) as error:
        create_purchase(
            session, "buyer", "same-key", purchase_request(sale, (2,))
        )
    assert error.value.code == "IDEMPOTENCY_KEY_REUSED"


def test_concurrent_buyers_cannot_take_the_same_last_unit(session):
    sale = create_open_sale(session, quantities=(1,), limits=(1,))
    request = purchase_request(sale, (1,))

    def attempt(user_id: str) -> str:
        with SessionLocal() as worker_session:
            try:
                create_purchase(worker_session, user_id, f"key-{user_id}", request)
                return "created"
            except AppError as error:
                return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(attempt, ("buyer-a", "buyer-b")))

    assert sorted(results) == ["PURCHASE_REJECTED", "created"]
    inventory = get_sale(session, sale.id).items[0]
    assert (inventory.available_quantity, inventory.held_quantity) == (0, 1)


def test_same_user_concurrency_cannot_bypass_limit(session):
    sale = create_open_sale(session, quantities=(4,), limits=(2,))
    request = purchase_request(sale, (2,))

    def attempt(key: str) -> str:
        with SessionLocal() as worker_session:
            try:
                create_purchase(worker_session, "same-buyer", key, request)
                return "created"
            except AppError as error:
                return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(attempt, ("key-a", "key-b")))

    assert sorted(results) == ["PURCHASE_REJECTED", "created"]
    inventory = get_sale(session, sale.id).items[0]
    assert inventory.held_quantity == 2


def test_expiry_returns_inventory_exactly_once(session):
    sale = create_open_sale(session, quantities=(2,), limits=(2,))
    order = create_purchase(session, "buyer", "expiry", purchase_request(sale, (2,)))
    reservation = session.scalar(
        select(Reservation).where(Reservation.order_id == order.id)
    )
    reservation.hold_expires_at = utc_now() - timedelta(seconds=1)
    session.commit()

    assert expire_due_reservations(session) == 1
    assert expire_due_reservations(session) == 0

    inventory = get_sale(session, sale.id).items[0]
    refreshed_order = session.get(Order, order.id)
    assert (inventory.available_quantity, inventory.held_quantity) == (2, 0)
    assert refreshed_order.status == "EXPIRED"
