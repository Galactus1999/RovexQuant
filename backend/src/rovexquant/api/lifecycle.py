"""Application startup validation and durable background worker lifecycle."""
import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from rovexquant.market_data.upstox_credentials import (
    validate_postgres_schema,
    validate_storage_configuration,
)
from rovexquant.market_data.eod_store import validate_market_schema
from rovexquant.market_data.eod_worker import run_ingestion_worker, worker_enabled


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Hosted Docker defaults to PostgreSQL and must not silently start with SQLite.
    validate_storage_configuration()
    validate_postgres_schema()
    validate_market_schema()
    worker = asyncio.create_task(run_ingestion_worker()) if worker_enabled() else None
    try:
        yield
    finally:
        if worker is not None:
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
