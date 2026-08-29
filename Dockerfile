FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN useradd --create-home --uid 10001 appuser

COPY --chown=appuser:appuser pyproject.toml alembic.ini ./
COPY --chown=appuser:appuser migrations ./migrations
COPY --chown=appuser:appuser src ./src
COPY --chown=appuser:appuser tests ./tests

RUN pip install --no-cache-dir ".[dev]"

USER appuser

CMD ["uvicorn", "limited_goods.main:app", "--host", "0.0.0.0", "--port", "8000"]
