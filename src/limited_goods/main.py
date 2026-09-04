from __future__ import annotations

import logging
from time import perf_counter
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from sqlalchemy import text

from limited_goods.db import SessionLocal, import_models
from limited_goods.errors import AppError
from limited_goods.logging import configure_logging
from limited_goods.metrics import HTTP_DURATION, HTTP_REQUESTS
from limited_goods.payments.router import router as payments_router
from limited_goods.purchases.router import router as purchases_router
from limited_goods.sales.router import router as sales_router


configure_logging()
import_models()
logger = logging.getLogger(__name__)

app = FastAPI(title="Limited Goods", version="0.1.0")
app.include_router(sales_router)
app.include_router(purchases_router)
app.include_router(payments_router)


@app.exception_handler(AppError)
async def app_error_handler(_: Request, error: AppError) -> JSONResponse:
    return JSONResponse(
        status_code=error.status_code,
        content={
            "error": {
                "code": error.code,
                "message": error.message,
                "details": error.details,
            }
        },
    )


@app.middleware("http")
async def request_metrics(request: Request, call_next):
    request_id = request.headers.get("X-Request-Id", str(uuid4()))
    started = perf_counter()
    status_code = 500
    try:
        response = await call_next(request)
        status_code = response.status_code
    except Exception:
        logger.exception(
            "unhandled request error",
            extra={"event": "http_unhandled_error", "request_id": request_id},
        )
        raise
    finally:
        route = request.scope.get("route")
        path = getattr(route, "path", request.url.path)
        duration = perf_counter() - started
        HTTP_REQUESTS.labels(request.method, path, str(status_code)).inc()
        HTTP_DURATION.labels(request.method, path).observe(duration)
    response.headers["X-Request-Id"] = request_id
    logger.info(
        "request completed",
        extra={"event": "http_request", "request_id": request_id},
    )
    return response


@app.get("/health/live", tags=["health"])
def liveness() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready", tags=["health"])
def readiness() -> dict[str, str]:
    with SessionLocal() as session:
        session.execute(text("SELECT 1"))
    return {"status": "ready"}


@app.get("/metrics", include_in_schema=False)
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
