from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, status
from sqlalchemy.orm import Session

from limited_goods.db import get_session
from limited_goods.purchases.schemas import OrderView, PurchaseRequest
from limited_goods.purchases.service import create_purchase, get_order_for_user


router = APIRouter(tags=["purchases"])
SessionDependency = Annotated[Session, Depends(get_session)]
UserHeader = Annotated[str, Header(alias="X-User-Id", min_length=1, max_length=100)]
IdempotencyHeader = Annotated[
    str, Header(alias="Idempotency-Key", min_length=1, max_length=100)
]


@router.post("/purchases", response_model=OrderView, status_code=status.HTTP_201_CREATED)
def create_purchase_endpoint(
    request: PurchaseRequest,
    session: SessionDependency,
    user_id: UserHeader,
    idempotency_key: IdempotencyHeader,
) -> OrderView:
    return create_purchase(session, user_id, idempotency_key, request)


@router.get("/orders/{order_id}", response_model=OrderView)
def get_order_endpoint(
    order_id: UUID, session: SessionDependency, user_id: UserHeader
) -> OrderView:
    return get_order_for_user(session, order_id, user_id)
