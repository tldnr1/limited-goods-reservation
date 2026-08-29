from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, status
from sqlalchemy.orm import Session

from limited_goods.config import get_settings
from limited_goods.db import get_session
from limited_goods.errors import AppError
from limited_goods.payments.provider import MockPgClient, PaymentProvider
from limited_goods.payments.schemas import (
    PaymentCallback,
    PaymentCallbackResult,
    PaymentStartRequest,
)
from limited_goods.payments.service import apply_payment_result, start_payment
from limited_goods.purchases.schemas import OrderView


router = APIRouter(tags=["payments"])
SessionDependency = Annotated[Session, Depends(get_session)]
UserHeader = Annotated[str, Header(alias="X-User-Id", min_length=1, max_length=100)]
IdempotencyHeader = Annotated[
    str, Header(alias="Idempotency-Key", min_length=1, max_length=100)
]


def get_payment_provider() -> PaymentProvider:
    return MockPgClient()


@router.post(
    "/orders/{order_id}/payment-attempts",
    response_model=OrderView,
    status_code=status.HTTP_201_CREATED,
)
def start_payment_endpoint(
    order_id: UUID,
    request: PaymentStartRequest,
    session: SessionDependency,
    user_id: UserHeader,
    idempotency_key: IdempotencyHeader,
    provider: Annotated[PaymentProvider, Depends(get_payment_provider)],
) -> OrderView:
    return start_payment(
        session, provider, order_id, user_id, idempotency_key, request
    )


@router.post(
    "/internal/payments/callback", response_model=PaymentCallbackResult
)
def payment_callback_endpoint(
    callback: PaymentCallback,
    session: SessionDependency,
    mock_secret: Annotated[str, Header(alias="X-Mock-PG-Secret")],
) -> PaymentCallbackResult:
    if mock_secret != get_settings().mock_pg_secret:
        raise AppError(401, "INVALID_PROVIDER_SECRET", "결제사 인증 정보가 올바르지 않습니다.")
    return apply_payment_result(session, callback)
