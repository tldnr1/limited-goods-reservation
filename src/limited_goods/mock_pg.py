from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging
from uuid import uuid4

from fastapi import FastAPI, Header, HTTPException
import httpx

from limited_goods.config import get_settings
from limited_goods.logging import configure_logging
from limited_goods.payments.schemas import (
    PaymentCallback,
    ProviderPaymentCreate,
    ProviderPaymentView,
)


configure_logging()
logger = logging.getLogger(__name__)
app = FastAPI(title="Limited Goods Mock PG", version="0.1.0")


@dataclass
class MockPayment:
    provider_reference: str
    request: ProviderPaymentCreate
    status: str


payments: dict[str, MockPayment] = {}
payments_lock = asyncio.Lock()


def verify_secret(secret: str) -> None:
    if secret != get_settings().mock_pg_secret:
        raise HTTPException(status_code=401, detail="invalid mock pg secret")


async def send_callback(payment: MockPayment, result: str) -> None:
    callback = PaymentCallback(
        payment_attempt_id=payment.request.payment_attempt_id,
        provider_reference=payment.provider_reference,
        result=result,
    )
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            response = await client.post(
                payment.request.callback_url,
                json=callback.model_dump(mode="json"),
                headers={"X-Mock-PG-Secret": get_settings().mock_pg_secret},
            )
            response.raise_for_status()
    except httpx.HTTPError:
        logger.exception(
            "mock pg callback failed",
            extra={
                "event": "mock_pg_callback_failed",
                "payment_attempt_id": str(payment.request.payment_attempt_id),
            },
        )


async def resolve_payment(payment: MockPayment) -> None:
    scenario = payment.request.scenario
    delay = payment.request.delay_seconds if scenario == "DELAYED_SUCCESS" else 0.2
    await asyncio.sleep(delay)
    result = "FAILED" if scenario == "FAILURE" else "SUCCEEDED"
    async with payments_lock:
        payment.status = result
    await send_callback(payment, result)
    if scenario == "DUPLICATE_SUCCESS":
        await asyncio.sleep(0.1)
        await send_callback(payment, result)


@app.post("/payments", response_model=ProviderPaymentView, status_code=201)
async def create_payment(
    request: ProviderPaymentCreate,
    x_mock_pg_secret: str = Header(alias="X-Mock-PG-Secret"),
) -> ProviderPaymentView:
    verify_secret(x_mock_pg_secret)
    provider_reference = f"mock_{uuid4().hex}"
    initial_status = "UNKNOWN" if request.scenario == "UNKNOWN" else "PROCESSING"
    payment = MockPayment(
        provider_reference=provider_reference,
        request=request,
        status=initial_status,
    )
    async with payments_lock:
        payments[provider_reference] = payment
    if request.scenario != "UNKNOWN":
        asyncio.create_task(resolve_payment(payment))
    return ProviderPaymentView(
        provider_reference=provider_reference, status=initial_status
    )


@app.get("/payments/{provider_reference}", response_model=ProviderPaymentView)
async def get_payment(
    provider_reference: str,
    x_mock_pg_secret: str = Header(alias="X-Mock-PG-Secret"),
) -> ProviderPaymentView:
    verify_secret(x_mock_pg_secret)
    async with payments_lock:
        payment = payments.get(provider_reference)
        if payment is None:
            raise HTTPException(status_code=404, detail="payment not found")
        return ProviderPaymentView(
            provider_reference=provider_reference, status=payment.status
        )


@app.get("/health/live", tags=["health"])
def liveness() -> dict[str, str]:
    return {"status": "ok"}
