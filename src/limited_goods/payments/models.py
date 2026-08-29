from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import BigInteger, CheckConstraint, DateTime, ForeignKey, Index, String, text
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from limited_goods.clock import utc_now
from limited_goods.db import Base


class PaymentAttemptStatus:
    CREATED = "CREATED"
    PROCESSING = "PROCESSING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    UNKNOWN = "UNKNOWN"

    BLOCKING = (PROCESSING, UNKNOWN)


class PaymentAttempt(Base):
    __tablename__ = "payment_attempts"
    __table_args__ = (
        CheckConstraint("amount > 0", name="positive_amount"),
        CheckConstraint(
            "status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')",
            name="valid_status",
        ),
        Index(
            "uq_payment_attempt_order_idempotency",
            "order_id",
            "idempotency_key",
            unique=True,
        ),
        Index(
            "uq_payment_attempt_one_success",
            "order_id",
            unique=True,
            postgresql_where=text("status = 'SUCCEEDED'"),
        ),
        Index(
            "uq_payment_attempt_one_blocking",
            "order_id",
            unique=True,
            postgresql_where=text("status IN ('PROCESSING', 'UNKNOWN')"),
        ),
    )

    id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), primary_key=True, default=uuid4)
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="CASCADE"), index=True
    )
    status: Mapped[str] = mapped_column(String(20), default=PaymentAttemptStatus.CREATED)
    amount: Mapped[int] = mapped_column(BigInteger)
    idempotency_key: Mapped[str] = mapped_column(String(100))
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    scenario: Mapped[str] = mapped_column(String(30))
    provider_reference: Mapped[str | None] = mapped_column(
        String(100), unique=True, nullable=True
    )
    provider_payload: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    order: Mapped["Order"] = relationship(back_populates="payment_attempts")


from limited_goods.purchases.models import Order  # noqa: E402
