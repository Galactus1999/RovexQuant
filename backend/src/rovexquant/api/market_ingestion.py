"""Authenticated read-only views of durable EOD ingestion state."""
from datetime import date
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from rovexquant.market_data.eod_store import (
    EodStoreError, latest_ingestion_status, symbol_history,
)
from rovexquant.security.dependencies import require_app_session

router = APIRouter()


def _store_error(error: EodStoreError) -> HTTPException:
    return HTTPException(status_code=error.status_code, detail=str(error))


@router.get(
    "/api/v1/app/market-data/ingestion/status",
    dependencies=[Depends(require_app_session)],
)
def app_market_ingestion_status(exchange: Literal["nse", "bse"] = "nse"):
    try:
        return latest_ingestion_status(exchange)
    except EodStoreError as error:
        raise _store_error(error) from error


@router.get(
    "/api/v1/app/market-data/ingestion/history/{exchange}/{symbol}",
    dependencies=[Depends(require_app_session)],
)
def app_market_ingestion_history(
    exchange: Literal["nse", "bse"],
    symbol: str,
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    limit: int = Query(1000, ge=1, le=2000),
):
    try:
        bars = symbol_history(
            exchange, symbol, from_date=from_date, to_date=to_date, limit=limit
        )
        return {
            "store": "persistent-eod-store",
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "data_mode": "END_OF_DAY",
            "bars": bars,
            "count": len(bars),
        }
    except EodStoreError as error:
        raise _store_error(error) from error
