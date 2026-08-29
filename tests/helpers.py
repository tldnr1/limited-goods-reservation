from __future__ import annotations

from datetime import timedelta

from sqlalchemy.orm import Session

from limited_goods.clock import utc_now
from limited_goods.payments.provider import PaymentProvider, ProviderStatus
from limited_goods.payments.schemas import PaymentStartRequest
from limited_goods.sales.schemas import SaleCreate, SaleItemCreate, SaleView
from limited_goods.sales.service import create_sale


def create_open_sale(
    session: Session,
    *,
    quantities: tuple[int, ...] = (5, 3),
    limits: tuple[int, ...] = (3, 2),
) -> SaleView:
    return create_sale(
        session,
        SaleCreate(
            name="테스트 판매",
            opens_at=utc_now() - timedelta(minutes=1),
            items=[
                SaleItemCreate(
                    name=f"상품 {index + 1}",
                    price=10_000 * (index + 1),
                    total_quantity=quantity,
                    per_user_limit=limits[index],
                )
                for index, quantity in enumerate(quantities)
            ],
        ),
    )


class FakePaymentProvider(PaymentProvider):
    def __init__(self, create_status: str = "PROCESSING") -> None:
        self.create_status = create_status
        self.statuses: dict[str, str] = {}

    def create_payment(
        self, payment_attempt_id, amount: int, request: PaymentStartRequest
    ) -> ProviderStatus:
        reference = f"fake_{payment_attempt_id.hex}"
        self.statuses[reference] = self.create_status
        return ProviderStatus(
            provider_reference=reference,
            status=self.create_status,
            payload={"provider_reference": reference, "status": self.create_status},
        )

    def get_status(self, provider_reference: str) -> ProviderStatus:
        status = self.statuses[provider_reference]
        return ProviderStatus(
            provider_reference=provider_reference,
            status=status,
            payload={"provider_reference": provider_reference, "status": status},
        )
