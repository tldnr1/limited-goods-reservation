from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field, field_validator


class SaleItemCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    price: int = Field(gt=0)
    total_quantity: int = Field(gt=0)
    per_user_limit: int = Field(gt=0)

    @field_validator("per_user_limit")
    @classmethod
    def limit_cannot_exceed_stock(cls, value: int, info):
        total = info.data.get("total_quantity")
        if total is not None and value > total:
            raise ValueError("per_user_limit cannot exceed total_quantity")
        return value


class SaleCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    opens_at: datetime
    items: list[SaleItemCreate] = Field(min_length=1)

    @field_validator("opens_at")
    @classmethod
    def opens_at_requires_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("opens_at must include a timezone")
        return value


class SaleItemView(BaseModel):
    id: UUID
    name: str
    price: int
    per_user_limit: int
    total_quantity: int
    available_quantity: int
    held_quantity: int
    sold_quantity: int


class SaleView(BaseModel):
    id: UUID
    name: str
    opens_at: datetime
    items: list[SaleItemView]
