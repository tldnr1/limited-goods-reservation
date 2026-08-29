from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


PaymentScenario = Literal[
    "SUCCESS", "FAILURE", "DELAYED_SUCCESS", "DUPLICATE_SUCCESS", "UNKNOWN"
]
PaymentResult = Literal["SUCCEEDED", "FAILED"]


class PaymentStartRequest(BaseModel):
    scenario: PaymentScenario = "SUCCESS"
    delay_seconds: float = Field(default=2.0, ge=0.1, le=60.0)


class ProviderPaymentCreate(BaseModel):
    payment_attempt_id: UUID
    amount: int
    scenario: PaymentScenario
    delay_seconds: float
    callback_url: str


class ProviderPaymentView(BaseModel):
    provider_reference: str
    status: str


class PaymentCallback(BaseModel):
    payment_attempt_id: UUID
    provider_reference: str
    result: PaymentResult


class PaymentCallbackResult(BaseModel):
    applied: bool
    duplicate: bool
