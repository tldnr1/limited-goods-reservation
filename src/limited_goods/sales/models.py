from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import BigInteger, CheckConstraint, DateTime, ForeignKey, Integer, String
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from limited_goods.clock import utc_now
from limited_goods.db import Base


class SaleEvent(Base):
    __tablename__ = "sale_events"

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    name: Mapped[str] = mapped_column(String(200))
    opens_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now
    )

    items: Mapped[list[SaleItem]] = relationship(
        back_populates="sale_event", cascade="all, delete-orphan"
    )


class SaleItem(Base):
    __tablename__ = "sale_items"
    __table_args__ = (
        CheckConstraint("price > 0", name="positive_price"),
        CheckConstraint("per_user_limit > 0", name="positive_per_user_limit"),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    sale_event_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("sale_events.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(200))
    price: Mapped[int] = mapped_column(BigInteger)
    per_user_limit: Mapped[int] = mapped_column(Integer)

    sale_event: Mapped[SaleEvent] = relationship(back_populates="items")
    inventory: Mapped[Inventory] = relationship(
        back_populates="sale_item", cascade="all, delete-orphan", uselist=False
    )


class Inventory(Base):
    __tablename__ = "inventories"
    __table_args__ = (
        CheckConstraint("total_quantity >= 0", name="nonnegative_total"),
        CheckConstraint("available_quantity >= 0", name="nonnegative_available"),
        CheckConstraint("held_quantity >= 0", name="nonnegative_held"),
        CheckConstraint("sold_quantity >= 0", name="nonnegative_sold"),
        CheckConstraint(
            "total_quantity = available_quantity + held_quantity + sold_quantity",
            name="quantity_sum",
        ),
    )

    sale_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("sale_items.id", ondelete="CASCADE"),
        primary_key=True,
    )
    total_quantity: Mapped[int] = mapped_column(Integer)
    available_quantity: Mapped[int] = mapped_column(Integer)
    held_quantity: Mapped[int] = mapped_column(Integer, default=0)
    sold_quantity: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    sale_item: Mapped[SaleItem] = relationship(back_populates="inventory")
