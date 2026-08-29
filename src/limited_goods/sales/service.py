from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from limited_goods.errors import AppError
from limited_goods.sales.models import Inventory, SaleEvent, SaleItem
from limited_goods.sales.schemas import SaleCreate, SaleItemView, SaleView


def create_sale(session: Session, request: SaleCreate) -> SaleView:
    sale = SaleEvent(name=request.name, opens_at=request.opens_at)
    for requested_item in request.items:
        item = SaleItem(
            name=requested_item.name,
            price=requested_item.price,
            per_user_limit=requested_item.per_user_limit,
        )
        item.inventory = Inventory(
            total_quantity=requested_item.total_quantity,
            available_quantity=requested_item.total_quantity,
            held_quantity=0,
            sold_quantity=0,
        )
        sale.items.append(item)
    session.add(sale)
    session.commit()
    return get_sale(session, sale.id)


def get_sale(session: Session, sale_id: UUID) -> SaleView:
    sale = session.scalar(
        select(SaleEvent)
        .where(SaleEvent.id == sale_id)
        .options(selectinload(SaleEvent.items).selectinload(SaleItem.inventory))
    )
    if sale is None:
        raise AppError(404, "SALE_NOT_FOUND", "판매를 찾을 수 없습니다.")
    return SaleView(
        id=sale.id,
        name=sale.name,
        opens_at=sale.opens_at,
        items=[
            SaleItemView(
                id=item.id,
                name=item.name,
                price=item.price,
                per_user_limit=item.per_user_limit,
                total_quantity=item.inventory.total_quantity,
                available_quantity=item.inventory.available_quantity,
                held_quantity=item.inventory.held_quantity,
                sold_quantity=item.inventory.sold_quantity,
            )
            for item in sale.items
        ],
    )
