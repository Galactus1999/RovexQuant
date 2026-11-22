"""Application startup validation; schema ownership remains with Alembic."""
from contextlib import asynccontextmanager

from fastapi import FastAPI

from rovexquant.market_data.upstox_credentials import (
    validate_postgres_schema,
    validate_storage_configuration,
)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Hosted Docker defaults to PostgreSQL and must not silently start with SQLite.
    validate_storage_configuration()
    validate_postgres_schema()
    yield
