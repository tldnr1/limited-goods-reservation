from __future__ import annotations

import pytest
from sqlalchemy import text

from limited_goods.db import Base, SessionLocal, engine, import_models


@pytest.fixture(scope="session", autouse=True)
def database_schema():
    import_models()
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture(autouse=True)
def clean_database(database_schema):
    with engine.begin() as connection:
        connection.execute(
            text(
                "TRUNCATE TABLE payment_attempts, reservations, order_items, orders, "
                "inventories, sale_items, sale_events CASCADE"
            )
        )


@pytest.fixture
def session():
    with SessionLocal() as value:
        yield value
