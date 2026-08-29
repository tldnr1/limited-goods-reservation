from __future__ import annotations

import pytest

from limited_goods.errors import AppError
from limited_goods.payments.schemas import PaymentCallback, PaymentStartRequest
from limited_goods.payments.service import apply_payment_result, start_payment
from limited_goods.purchases.schemas import PurchaseItemRequest, PurchaseRequest
from limited_goods.purchases.service import create_purchase, get_order_for_user
from limited_goods.sales.service import get_sale
from tests.helpers import FakePaymentProvider, create_open_sale


def create_order(session):
    sale = create_open_sale(session, quantities=(2,), limits=(2,))
    order = create_purchase(
        session,
        "buyer",
        "purchase",
        PurchaseRequest(
            sale_event_id=sale.id,
            items=[PurchaseItemRequest(sale_item_id=sale.items[0].id, quantity=2)],
        ),
    )
    return sale, order


def test_duplicate_success_confirms_inventory_only_once(session):
    sale, order = create_order(session)
    provider = FakePaymentProvider()
    started = start_payment(
        session,
        provider,
        order.id,
        "buyer",
        "payment-1",
        PaymentStartRequest(scenario="SUCCESS"),
    )
    attempt = started.payment_attempts[0]
    callback = PaymentCallback(
        payment_attempt_id=attempt.id,
        provider_reference=attempt.provider_reference,
        result="SUCCEEDED",
    )

    first = apply_payment_result(session, callback)
    second = apply_payment_result(session, callback)

    inventory = get_sale(session, sale.id).items[0]
    confirmed = get_order_for_user(session, order.id, "buyer")
    assert first.applied is True
    assert second.duplicate is True
    assert confirmed.status == "CONFIRMED"
    assert (inventory.available_quantity, inventory.held_quantity, inventory.sold_quantity) == (
        0,
        0,
        2,
    )


def test_failed_payment_can_retry_under_the_same_order(session):
    _, order = create_order(session)
    provider = FakePaymentProvider()
    first = start_payment(
        session,
        provider,
        order.id,
        "buyer",
        "payment-1",
        PaymentStartRequest(scenario="FAILURE"),
    )
    first_attempt = first.payment_attempts[0]
    apply_payment_result(
        session,
        PaymentCallback(
            payment_attempt_id=first_attempt.id,
            provider_reference=first_attempt.provider_reference,
            result="FAILED",
        ),
    )

    second = start_payment(
        session,
        provider,
        order.id,
        "buyer",
        "payment-2",
        PaymentStartRequest(scenario="SUCCESS"),
    )

    assert second.id == order.id
    assert [attempt.status for attempt in second.payment_attempts] == [
        "FAILED",
        "PROCESSING",
    ]
    assert second.reservation.hold_expires_at == order.reservation.hold_expires_at


def test_unknown_payment_blocks_a_new_attempt(session):
    _, order = create_order(session)
    provider = FakePaymentProvider(create_status="UNKNOWN")
    started = start_payment(
        session,
        provider,
        order.id,
        "buyer",
        "payment-unknown",
        PaymentStartRequest(scenario="UNKNOWN"),
    )
    assert started.payment_attempts[0].status == "UNKNOWN"

    with pytest.raises(AppError) as error:
        start_payment(
            session,
            provider,
            order.id,
            "buyer",
            "payment-new",
            PaymentStartRequest(scenario="SUCCESS"),
        )
    assert error.value.code == "PAYMENT_ATTEMPT_BLOCKED"
