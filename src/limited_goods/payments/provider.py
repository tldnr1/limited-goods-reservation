from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

import httpx

from limited_goods.config import get_settings
from limited_goods.payments.schemas import PaymentStartRequest, ProviderPaymentCreate


@dataclass(frozen=True)
class ProviderStatus:
    provider_reference: str
    status: str
    payload: dict


class PaymentProvider:
    def create_payment(
        self, payment_attempt_id: UUID, amount: int, request: PaymentStartRequest
    ) -> ProviderStatus:
        raise NotImplementedError

    def get_status(self, provider_reference: str) -> ProviderStatus:
        raise NotImplementedError


class MockPgClient(PaymentProvider):
    def __init__(self) -> None:
        settings = get_settings()
        self.base_url = settings.mock_pg_url
        self.callback_url = settings.mock_pg_callback_url
        self.secret = settings.mock_pg_secret

    def create_payment(
        self, payment_attempt_id: UUID, amount: int, request: PaymentStartRequest
    ) -> ProviderStatus:
        body = ProviderPaymentCreate(
            payment_attempt_id=payment_attempt_id,
            amount=amount,
            scenario=request.scenario,
            delay_seconds=request.delay_seconds,
            callback_url=self.callback_url,
        )
        response = httpx.post(
            f"{self.base_url}/payments",
            json=body.model_dump(mode="json"),
            headers={"X-Mock-PG-Secret": self.secret},
            timeout=5,
        )
        response.raise_for_status()
        payload = response.json()
        return ProviderStatus(
            provider_reference=payload["provider_reference"],
            status=payload["status"],
            payload=payload,
        )

    def get_status(self, provider_reference: str) -> ProviderStatus:
        response = httpx.get(
            f"{self.base_url}/payments/{provider_reference}",
            headers={"X-Mock-PG-Secret": self.secret},
            timeout=5,
        )
        response.raise_for_status()
        payload = response.json()
        return ProviderStatus(
            provider_reference=provider_reference,
            status=payload["status"],
            payload=payload,
        )
