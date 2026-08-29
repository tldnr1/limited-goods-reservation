from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from limited_goods.db import get_session
from limited_goods.sales.schemas import SaleCreate, SaleView
from limited_goods.sales.service import create_sale, get_sale


router = APIRouter(tags=["sales"])
SessionDependency = Annotated[Session, Depends(get_session)]


@router.post("/admin/sales", response_model=SaleView, status_code=status.HTTP_201_CREATED)
def create_sale_endpoint(request: SaleCreate, session: SessionDependency) -> SaleView:
    return create_sale(session, request)


@router.get("/sales/{sale_id}", response_model=SaleView)
def get_sale_endpoint(sale_id: UUID, session: SessionDependency) -> SaleView:
    return get_sale(session, sale_id)
