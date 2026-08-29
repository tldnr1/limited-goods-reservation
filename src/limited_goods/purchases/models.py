from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from limited_goods.clock import utc_now
from limited_goods.db import Base


class OrderStatus:
    PAYMENT_PENDING = "PAYMENT_PENDING"
    PAYMENT_PROCESSING = "PAYMENT_PROCESSING"
    CONFIRMED = "CONFIRMED"
    EXPIRED = "EXPIRED"

    COUNTING = (PAYMENT_PENDING, PAYMENT_PROCESSING, CONFIRMED)


class Order(Base):
    __tablename__ = "orders"
    __table_args__ = (
        UniqueConstraint("user_id", "idempotency_key", name="uq_order_user_idempotency"),
        CheckConstraint("total_amount > 0", name="positive_total_amount"),
        CheckConstraint(
            "status IN ('PAYMENT_PENDING', 'PAYMENT_PROCESSING', 'CONFIRMED', 'EXPIRED')",
            name="valid_status",
        ),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    user_id: Mapped[str] = mapped_column(String(100), index=True)
    sale_event_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("sale_events.id"), index=True
    )
    status: Mapped[str] = mapped_column(String(32), default=OrderStatus.PAYMENT_PENDING)
    total_amount: Mapped[int] = mapped_column(BigInteger)
    idempotency_key: Mapped[str] = mapped_column(String(100))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    items: Mapped[list[OrderItem]] = relationship(
        back_populates="order", cascade="all, delete-orphan"
    )
    reservation: Mapped["Reservation"] = relationship(
        back_populates="order", cascade="all, delete-orphan", uselist=False
    )
    payment_attempts: Mapped[list["PaymentAttempt"]] = relationship(
        back_populates="order", cascade="all, delete-orphan"
    )


class OrderItem(Base):
    __tablename__ = "order_items"
    __table_args__ = (
        UniqueConstraint("order_id", "sale_item_id", name="uq_order_item_sale_item"),
        CheckConstraint("quantity > 0", name="positive_quantity"),
        CheckConstraint("unit_price > 0", name="positive_unit_price"),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="CASCADE"), index=True
    )
    sale_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("sale_items.id"), index=True
    )
    quantity: Mapped[int] = mapped_column(Integer)
    unit_price: Mapped[int] = mapped_column(BigInteger)

    order: Mapped[Order] = relationship(back_populates="items")


from limited_goods.payments.models import PaymentAttempt  # noqa: E402
from limited_goods.reservations.models import Reservation  # noqa: E402
