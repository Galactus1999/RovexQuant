"""Market HTTP/WebSocket routes; quote and stream behavior belongs to market services."""
from __future__ import annotations

import asyncio
from dataclasses import asdict
from datetime import date, datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, model_validator

from rovexquant.api.dependencies import require_market_gateway
from rovexquant.auth import AppAuthError, bearer_claims
from rovexquant.market_data.eod_store import EodStoreError
from rovexquant.market_data.integrity_service import latest_data_integrity
from rovexquant.market_data.data_health import get_market_data_health
from rovexquant.market_data.instruments import (
    InstrumentMasterError,
    get_nse_equity_instruments,
    get_nse_index_instruments,
)
from rovexquant.market_data.providers import QuoteInstrument
from rovexquant.market_data.service import (
    MarketGatewayError,
    fetch_quotes,
    provider_status,
)
from rovexquant.market_data.streaming import StreamError, stream_upstox_market_data
from rovexquant.market_data.upstox_candles import UpstoxCandleError, fetch_intraday_candles
from rovexquant.security.request_controls import backtest_rate_limit
from rovexquant.security.dependencies import require_app_session

router = APIRouter()


class MarketInstrumentInput(BaseModel):
    symbol: str = Field(min_length=1, max_length=80)
    upstox_instrument_key: str | None = Field(default=None, max_length=120)
    dhan_exchange_segment: str | None = Field(default=None, max_length=30)
    dhan_security_id: str | None = Field(default=None, max_length=30)


class MarketQuotesRequest(BaseModel):
    instruments: list[MarketInstrumentInput] = Field(min_length=1, max_length=500)
    provider_order: list[str] = Field(
        default_factory=lambda: ["upstox", "dhan"], min_length=1, max_length=2
    )

    @model_validator(mode="after")
    def unique_symbols(self):
        symbols = [item.symbol for item in self.instruments]
        if len(symbols) != len(set(symbols)):
            raise ValueError("Instrument symbols must be unique within a quote request")
        if len(self.provider_order) != len(set(self.provider_order)):
            raise ValueError("Provider order must not contain duplicates")
        return self


async def _quote_response(request: MarketQuotesRequest) -> dict[str, object]:
    instruments = [
        QuoteInstrument(
            symbol=item.symbol,
            upstox_instrument_key=item.upstox_instrument_key,
            dhan_exchange_segment=item.dhan_exchange_segment,
            dhan_security_id=item.dhan_security_id,
        )
        for item in request.instruments
    ]
    provider, quotes, fallback_used = await fetch_quotes(instruments, request.provider_order)
    quoted_symbols = {item.symbol for item in quotes}
    return {
        "provider": provider,
        "fallback_used": fallback_used,
        "received_at": datetime.now(timezone.utc),
        "complete": len(quoted_symbols) == len(instruments),
        "missing_symbols": [item.symbol for item in instruments if item.symbol not in quoted_symbols],
        "quotes": [asdict(item) for item in quotes],
    }


@router.get(
    "/api/v1/app/market/providers",
    dependencies=[Depends(require_app_session)],
)
def app_market_providers():
    return {"providers": provider_status()}


@router.get(
    "/api/v1/app/market/index-instruments",
    dependencies=[Depends(require_app_session)],
)
async def app_market_index_instruments():
    try:
        instruments = await get_nse_index_instruments()
        return {"provider": "upstox_instrument_master", "instruments": instruments}
    except InstrumentMasterError as error:
        from fastapi import HTTPException

        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/market/equity-instruments",
    dependencies=[Depends(require_app_session)],
)
async def app_market_equity_instruments():
    """Return the official NSE cash-equity BOD master for authenticated market subscriptions."""
    try:
        instruments = await get_nse_equity_instruments()
        return {
            "provider": "upstox_instrument_master",
            "data_mode": "INSTRUMENT_REFERENCE",
            "instrument_count": len(instruments),
            "instruments": instruments,
        }
    except InstrumentMasterError as error:
        from fastapi import HTTPException

        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/market/candles",
    dependencies=[Depends(require_app_session), Depends(backtest_rate_limit)],
)
async def app_intraday_market_candles(
    exchange: str = Query(..., min_length=3, max_length=3),
    symbol: str = Query(..., min_length=1, max_length=80),
    interval: str = Query(..., min_length=2, max_length=3),
    from_date: date = Query(..., alias="from"),
    to_date: __import__("datetime").date = Query(..., alias="to"),
):
    """Return validated NSE intraday candles using the server-side Upstox token vault."""
    try:
        return await fetch_intraday_candles(
            exchange, symbol, interval, from_date, to_date
        )
    except UpstoxCandleError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.post(
    "/api/v1/app/market/quotes",
    dependencies=[Depends(require_app_session)],
)
async def app_market_quotes(request: MarketQuotesRequest):
    try:
        return await _quote_response(request)
    except MarketGatewayError as error:
        from fastapi import HTTPException

        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.websocket("/api/v1/app/market/stream")
async def app_market_stream(websocket: WebSocket):
    try:
        bearer_claims(websocket.headers.get("authorization"))
    except AppAuthError:
        await websocket.close(code=4401, reason="Authentication required")
        return

    await websocket.accept()
    try:
        subscription = await asyncio.wait_for(websocket.receive_json(), timeout=10)
        if not isinstance(subscription, dict):
            raise StreamError("Invalid subscription request")
        keys = subscription.get("instrument_keys")
        mode = subscription.get("mode", "full")
        if not isinstance(keys, list) or any(not isinstance(key, str) for key in keys):
            raise StreamError("instrument_keys must be a list of verified NSE instrument keys")
        await stream_upstox_market_data(websocket, keys, mode)
    except (StreamError, asyncio.TimeoutError) as error:
        retryable = isinstance(error, asyncio.TimeoutError) or (
            isinstance(error, StreamError) and error.retryable
        )
        await websocket.send_json({
            "type": "error",
            "message": str(error),
            "retryable": retryable,
        })
        await websocket.close(
            code=1013 if retryable else 4400,
            reason="Temporary upstream outage" if retryable else "Invalid or unavailable market stream",
        )
    except WebSocketDisconnect:
        return
    except Exception:
        await websocket.send_json({"type": "error", "message": "Market stream disconnected"})
        await websocket.close(code=1011, reason="Market stream unavailable")


@router.get(
    "/api/v1/market/providers",
    dependencies=[Depends(require_market_gateway)],
)
def market_providers():
    return {"providers": provider_status()}


@router.post(
    "/api/v1/market/quotes",
    dependencies=[Depends(require_market_gateway)],
)
async def market_quotes(request: MarketQuotesRequest):
    try:
        return await _quote_response(request)
    except MarketGatewayError as error:
        from fastapi import HTTPException

        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/market/data-integrity",
    dependencies=[Depends(require_app_session)],
)
def app_market_data_integrity(exchange: str = Query(default="NSE", pattern="^(NSE|BSE)$")):
    """Return the latest official-source reconciliation and historical backfill checkpoints."""
    try:
        return latest_data_integrity(exchange)
    except EodStoreError as error:
        from fastapi import HTTPException

        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/market/data-health",
    dependencies=[Depends(require_app_session)],
)
def app_market_data_health(exchange: str = Query(default="NSE", pattern="^(NSE|BSE)$")):
    """Return source freshness, ingestion outcomes, historical coverage and reconciliation status."""
    try:
        return get_market_data_health(exchange)
    except EodStoreError as error:
        from fastapi import HTTPException

        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
