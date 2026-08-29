"""Initial schema.

Revision ID: 0001
Revises:
"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "sale_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("opens_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint("id", name="pk_sale_events"),
    )
    op.create_index("ix_sale_events_opens_at", "sale_events", ["opens_at"])

    op.create_table(
        "sale_items",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sale_event_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("price", sa.BigInteger(), nullable=False),
        sa.Column("per_user_limit", sa.Integer(), nullable=False),
        sa.CheckConstraint("price > 0", name="ck_sale_items_positive_price"),
        sa.CheckConstraint(
            "per_user_limit > 0", name="ck_sale_items_positive_per_user_limit"
        ),
        sa.ForeignKeyConstraint(
            ["sale_event_id"], ["sale_events.id"], ondelete="CASCADE", name="fk_sale_items_sale_event_id_sale_events"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_sale_items"),
    )
    op.create_index("ix_sale_items_sale_event_id", "sale_items", ["sale_event_id"])

    op.create_table(
        "inventories",
        sa.Column("sale_item_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("total_quantity", sa.Integer(), nullable=False),
        sa.Column("available_quantity", sa.Integer(), nullable=False),
        sa.Column("held_quantity", sa.Integer(), nullable=False),
        sa.Column("sold_quantity", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("total_quantity >= 0", name="ck_inventories_nonnegative_total"),
        sa.CheckConstraint("available_quantity >= 0", name="ck_inventories_nonnegative_available"),
        sa.CheckConstraint("held_quantity >= 0", name="ck_inventories_nonnegative_held"),
        sa.CheckConstraint("sold_quantity >= 0", name="ck_inventories_nonnegative_sold"),
        sa.CheckConstraint(
            "total_quantity = available_quantity + held_quantity + sold_quantity",
            name="ck_inventories_quantity_sum",
        ),
        sa.ForeignKeyConstraint(
            ["sale_item_id"], ["sale_items.id"], ondelete="CASCADE", name="fk_inventories_sale_item_id_sale_items"
        ),
        sa.PrimaryKeyConstraint("sale_item_id", name="pk_inventories"),
    )

    op.create_table(
        "orders",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", sa.String(length=100), nullable=False),
        sa.Column("sale_event_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("total_amount", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=100), nullable=False),
        sa.Column("request_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("total_amount > 0", name="ck_orders_positive_total_amount"),
        sa.CheckConstraint(
            "status IN ('PAYMENT_PENDING', 'PAYMENT_PROCESSING', 'CONFIRMED', 'EXPIRED')",
            name="ck_orders_valid_status",
        ),
        sa.ForeignKeyConstraint(
            ["sale_event_id"], ["sale_events.id"], name="fk_orders_sale_event_id_sale_events"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_orders"),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_order_user_idempotency"),
    )
    op.create_index("ix_orders_user_id", "orders", ["user_id"])
    op.create_index("ix_orders_sale_event_id", "orders", ["sale_event_id"])

    op.create_table(
        "order_items",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("order_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sale_item_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("quantity", sa.Integer(), nullable=False),
        sa.Column("unit_price", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("quantity > 0", name="ck_order_items_positive_quantity"),
        sa.CheckConstraint("unit_price > 0", name="ck_order_items_positive_unit_price"),
        sa.ForeignKeyConstraint(
            ["order_id"], ["orders.id"], ondelete="CASCADE", name="fk_order_items_order_id_orders"
        ),
        sa.ForeignKeyConstraint(
            ["sale_item_id"], ["sale_items.id"], name="fk_order_items_sale_item_id_sale_items"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_order_items"),
        sa.UniqueConstraint("order_id", "sale_item_id", name="uq_order_item_sale_item"),
    )
    op.create_index("ix_order_items_order_id", "order_items", ["order_id"])
    op.create_index("ix_order_items_sale_item_id", "order_items", ["sale_item_id"])

    op.create_table(
        "reservations",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("order_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("hold_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("confirmation_deadline", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED')", name="ck_reservations_valid_status"
        ),
        sa.ForeignKeyConstraint(
            ["order_id"], ["orders.id"], ondelete="CASCADE", name="fk_reservations_order_id_orders"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_reservations"),
        sa.UniqueConstraint("order_id", name="uq_reservations_order_id"),
    )
    op.create_index("ix_reservations_hold_expires_at", "reservations", ["hold_expires_at"])
    op.create_index("ix_reservations_confirmation_deadline", "reservations", ["confirmation_deadline"])

    op.create_table(
        "payment_attempts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("order_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("amount", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=100), nullable=False),
        sa.Column("request_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("scenario", sa.String(length=30), nullable=False),
        sa.Column("provider_reference", sa.String(length=100), nullable=True),
        sa.Column("provider_payload", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("amount > 0", name="ck_payment_attempts_positive_amount"),
        sa.CheckConstraint(
            "status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')",
            name="ck_payment_attempts_valid_status",
        ),
        sa.ForeignKeyConstraint(
            ["order_id"], ["orders.id"], ondelete="CASCADE", name="fk_payment_attempts_order_id_orders"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_payment_attempts"),
        sa.UniqueConstraint("provider_reference", name="uq_payment_attempts_provider_reference"),
    )
    op.create_index("ix_payment_attempts_order_id", "payment_attempts", ["order_id"])
    op.create_index(
        "uq_payment_attempt_order_idempotency",
        "payment_attempts",
        ["order_id", "idempotency_key"],
        unique=True,
    )
    op.create_index(
        "uq_payment_attempt_one_success",
        "payment_attempts",
        ["order_id"],
        unique=True,
        postgresql_where=sa.text("status = 'SUCCEEDED'"),
    )
    op.create_index(
        "uq_payment_attempt_one_blocking",
        "payment_attempts",
        ["order_id"],
        unique=True,
        postgresql_where=sa.text("status IN ('PROCESSING', 'UNKNOWN')"),
    )


def downgrade() -> None:
    op.drop_table("payment_attempts")
    op.drop_table("reservations")
    op.drop_table("order_items")
    op.drop_table("orders")
    op.drop_table("inventories")
    op.drop_table("sale_items")
    op.drop_table("sale_events")
