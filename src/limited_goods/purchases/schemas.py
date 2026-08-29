from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field, field_validator


class PurchaseItemRequest(BaseModel):
    sale_item_id: UUID
    quantity: int = Field(gt=0)


class PurchaseRequest(BaseModel):
    sale_event_id: UUID
    items: list[PurchaseItemRequest] = Field(min_length=1)

    @field_validator("items")
    @classmethod
    def item_ids_must_be_unique(
        cls, items: list[PurchaseItemRequest]
    ) -> list[PurchaseItemRequest]:
        ids = [item.sale_item_id for item in items]
        if len(ids) != len(set(ids)):
            raise ValueError("sale_item_id must be unique")
        return items


class OrderItemView(BaseModel):
    sale_item_id: UUID
    quantity: int
    unit_price: int


class ReservationView(BaseModel):
    id: UUID
    status: str
    hold_expires_at: datetime
    confirmation_deadline: datetime | None


class PaymentAttemptView(BaseModel):
    id: UUID
    status: str
    amount: int
    scenario: str
    provider_reference: str | None
    started_at: datetime
    resolved_at: datetime | None


class OrderView(BaseModel):
    id: UUID
    user_id: str
    sale_event_id: UUID
    status: str
    total_amount: int
    items: list[OrderItemView]
    reservation: ReservationView
    payment_attempts: list[PaymentAttemptView]
    created_at: datetime
