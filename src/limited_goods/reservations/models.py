from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, String
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from limited_goods.clock import utc_now
from limited_goods.db import Base


class ReservationStatus:
    ACTIVE = "ACTIVE"
    CONFIRMED = "CONFIRMED"
    EXPIRED = "EXPIRED"


class Reservation(Base):
    __tablename__ = "reservations"
    __table_args__ = (
        CheckConstraint(
            "status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED')", name="valid_status"
        ),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="CASCADE"),
        unique=True,
    )
    status: Mapped[str] = mapped_column(String(20), default=ReservationStatus.ACTIVE)
    hold_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    confirmation_deadline: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, index=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    order: Mapped["Order"] = relationship(back_populates="reservation")


from limited_goods.purchases.models import Order  # noqa: E402
