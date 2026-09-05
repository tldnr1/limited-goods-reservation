from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import Barrier

import pytest
from sqlalchemy import event, func, select, text

from limited_goods.clock import utc_now
from limited_goods.config import get_settings
from limited_goods.db import SessionLocal
from limited_goods.errors import AppError
from limited_goods.purchases.models import Order, OrderItem
from limited_goods.purchases.schemas import PurchaseItemRequest, PurchaseRequest
from limited_goods.purchases.service import create_purchase
from limited_goods.reservations.models import Reservation
from limited_goods.reservations.service import expire_due_reservations
from limited_goods.sales.models import Inventory
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


def synchronize_purchase(session, barrier, event_name="after_flush_postexec"):
    assert session.connection().get_isolation_level() == "READ COMMITTED"
    session.execute(text("SET LOCAL statement_timeout = '10s'"))

    def wait_for_competitor(*args):
        barrier.wait(timeout=5)

    event.listen(session, event_name, wait_for_competitor, once=True)


def assert_purchase_row_counts(session, orders, items, reservations):
    assert session.scalar(select(func.count()).select_from(Order)) == orders
    assert session.scalar(select(func.count()).select_from(OrderItem)) == items
    assert session.scalar(select(func.count()).select_from(Reservation)) == reservations


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


@pytest.mark.parametrize("failure", ["stock", "limit"])
def test_multi_item_purchase_is_all_or_nothing(session, failure):
    sale = create_open_sale(
        session, quantities=(3, 1 if failure == "stock" else 3), limits=(3, 1)
    )
    create_purchase(
        session,
        "other" if failure == "stock" else "buyer",
        "first",
        purchase_request(sale, (1, 1)),
    )

    tentative_ids = []

    def capture_tentative_order(flushed_session, flush_context):
        tentative = flushed_session.scalar(
            select(Order).where(Order.idempotency_key == "all-or-nothing")
        )
        assert tentative is not None
        assert len(tentative.items) == 2
        assert_purchase_row_counts(flushed_session, 2, 4, 1)
        tentative_ids.append(tentative.id)

    event.listen(session, "after_flush_postexec", capture_tentative_order, once=True)

    before = get_sale(session, sale.id)
    with pytest.raises(AppError) as error:
        create_purchase(session, "buyer", "all-or-nothing", purchase_request(sale, (1, 1)))

    after = get_sale(session, sale.id)
    assert error.value.code == "PURCHASE_REJECTED"
    assert after == before
    assert len(tentative_ids) == 1
    with SessionLocal() as verification:
        assert verification.get(Order, tentative_ids[0]) is None
        assert_purchase_row_counts(verification, 1, 2, 1)


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
    barrier = Barrier(2)

    def attempt(user_id: str) -> str:
        with SessionLocal() as worker_session:
            synchronize_purchase(worker_session, barrier)
            try:
                create_purchase(worker_session, user_id, f"key-{user_id}", request)
                return "created"
            except AppError as error:
                return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(attempt, ("buyer-a", "buyer-b")))

    assert sorted(results) == ["PURCHASE_REJECTED", "created"]
    with SessionLocal() as verification:
        inventory = get_sale(verification, sale.id).items[0]
        assert (inventory.available_quantity, inventory.held_quantity) == (0, 1)
        assert_purchase_row_counts(verification, 1, 1, 1)


def test_same_user_concurrency_cannot_bypass_limit(session):
    sale = create_open_sale(session, quantities=(4,), limits=(2,))
    request = purchase_request(sale, (2,))
    barrier = Barrier(2)

    def attempt(key: str) -> str:
        with SessionLocal() as worker_session:
            synchronize_purchase(worker_session, barrier)
            try:
                create_purchase(worker_session, "same-buyer", key, request)
                return "created"
            except AppError as error:
                return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(attempt, ("key-a", "key-b")))

    assert sorted(results) == ["PURCHASE_REJECTED", "created"]
    with SessionLocal() as verification:
        inventory = get_sale(verification, sale.id).items[0]
        assert inventory.held_quantity == 2
        assert_purchase_row_counts(verification, 1, 1, 1)


def test_same_idempotency_key_race_returns_one_purchase(session):
    sale = create_open_sale(session, quantities=(1,), limits=(1,))
    request = purchase_request(sale, (1,))
    barrier = Barrier(2)

    def attempt(_):
        with SessionLocal() as worker_session:
            # Both initial lookups must miss before either inserts the unique key.
            synchronize_purchase(worker_session, barrier, "before_flush")
            return create_purchase(worker_session, "buyer", "same-key", request)

    with ThreadPoolExecutor(max_workers=2) as executor:
        first, second = list(executor.map(attempt, range(2)))

    assert first.id == second.id
    assert first.reservation.id == second.reservation.id
    with SessionLocal() as verification:
        assert_purchase_row_counts(verification, 1, 1, 1)
        inventory = get_sale(verification, sale.id).items[0]
        assert (inventory.available_quantity, inventory.held_quantity) == (0, 1)


def test_multi_item_concurrency_uses_same_lock_order(session):
    sale = create_open_sale(session, quantities=(1, 1), limits=(1, 1))
    request = purchase_request(sale, (1, 1))
    reversed_request = PurchaseRequest(
        sale_event_id=sale.id, items=list(reversed(request.items))
    )
    barrier = Barrier(2)

    def attempt(args):
        user_id, payload = args
        with SessionLocal() as worker_session:
            synchronize_purchase(worker_session, barrier)
            try:
                create_purchase(worker_session, user_id, "purchase", payload)
                return "created"
            except AppError as error:
                return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(attempt, (("a", request), ("b", reversed_request))))

    assert sorted(results) == ["PURCHASE_REJECTED", "created"]
    with SessionLocal() as verification:
        assert_purchase_row_counts(verification, 1, 2, 1)
        for inventory in get_sale(verification, sale.id).items:
            assert (inventory.available_quantity, inventory.held_quantity) == (0, 1)


def test_inventory_lock_refreshes_preloaded_state(session):
    sale = create_open_sale(session, quantities=(1,), limits=(1,))
    request = purchase_request(sale, (1,))
    inventory = session.get(Inventory, sale.items[0].id)
    assert inventory.available_quantity == 1
    with SessionLocal() as competitor:
        create_purchase(competitor, "other", "first", request)

    with pytest.raises(AppError) as error:
        create_purchase(session, "buyer", "second", request)

    assert error.value.code == "PURCHASE_REJECTED"
    assert_purchase_row_counts(session, 1, 1, 1)
    assert (inventory.available_quantity, inventory.held_quantity) == (0, 1)


def test_reservation_ttl_keeps_prelock_time(session, monkeypatch):
    sale = create_open_sale(session, quantities=(1,), limits=(1,))
    started_at = utc_now()
    current_time = [started_at]
    monkeypatch.setattr(
        "limited_goods.purchases.service.utc_now", lambda: current_time[0]
    )

    def advance_time(flushed_session, flush_context):
        current_time[0] += timedelta(minutes=2)

    event.listen(session, "after_flush_postexec", advance_time, once=True)
    order = create_purchase(session, "buyer", "ttl", purchase_request(sale, (1,)))

    assert order.reservation.hold_expires_at == started_at + timedelta(
        seconds=get_settings().reservation_ttl_seconds
    )


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
