"""Authenticated EOD and NSE MCP research API routes.

Routes own HTTP contracts and safe response mapping. Provider orchestration is
delegated to market_data application services.
"""
from datetime import date, datetime, timezone
import json
from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from pydantic import BaseModel, Field, model_validator

from rovexquant.market_data.nse_mcp import NseMcpError, extract_source_timestamp
from rovexquant.market_data.tejhq import TejHqError
from rovexquant.market_data import nse_mcp_service, tejhq_service
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import nse_tool_rate_limit

router = APIRouter()


def _tejhq_http_error(error: TejHqError) -> HTTPException:
    return HTTPException(status_code=error.status_code, detail=str(error))


@router.get(
    "/api/v1/app/research/market-data/providers",
    dependencies=[Depends(require_app_session)],
)
def app_research_data_providers():
    try:
        return {"providers": [tejhq_service.provider_status()]}
    except TejHqError as error:
        raise _tejhq_http_error(error) from error


@router.get(
    "/api/v1/app/research/market-data/ohlcv/{exchange}/{symbol}",
    dependencies=[Depends(require_app_session)],
)
async def app_research_eod_ohlcv(
    exchange: str,
    symbol: str,
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    adjusted: bool = False,
):
    try:
        provider_id, bars = await tejhq_service.fetch_ohlcv(
            exchange, symbol, from_date, to_date, adjusted=adjusted
        )
        return {
            "provider": provider_id,
            "data_mode": "END_OF_DAY",
            "adjusted": adjusted,
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "from": from_date,
            "to": to_date,
            "received_at": datetime.now(timezone.utc),
            "timestamp_semantics": "Trading date only; not an intraday or live timestamp",
            "bars": [item.model_dump() if hasattr(item, "model_dump") else item for item in bars],
        }
    except TejHqError as error:
        raise _tejhq_http_error(error) from error


@router.get(
    "/api/v1/app/research/market-data/delivery/{exchange}/{symbol}",
    dependencies=[Depends(require_app_session)],
)
async def app_research_eod_delivery(
    exchange: str,
    symbol: str,
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
):
    try:
        provider_id, rows = await tejhq_service.fetch_delivery(
            exchange, symbol, from_date, to_date
        )
        return {
            "provider": provider_id,
            "data_mode": "END_OF_DAY",
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "received_at": datetime.now(timezone.utc),
            "rows": rows,
        }
    except TejHqError as error:
        raise _tejhq_http_error(error) from error


@router.get(
    "/api/v1/app/research/market-data/snapshot/{exchange}",
    dependencies=[Depends(require_app_session)],
)
async def app_research_eod_snapshot(
    exchange: str,
    trading_date: date,
):
    try:
        provider_id, rows = await tejhq_service.fetch_snapshot(exchange, trading_date)
        return {
            "provider": provider_id,
            "data_mode": "END_OF_DAY",
            "exchange": exchange.upper(),
            "trading_date": trading_date,
            "received_at": datetime.now(timezone.utc),
            "rows": rows,
        }
    except TejHqError as error:
        raise _tejhq_http_error(error) from error


@router.get(
    "/api/v1/app/research/market-data/actions/{symbol}",
    dependencies=[Depends(require_app_session)],
)
async def app_research_corporate_actions(symbol: str):
    try:
        provider_id, rows = await tejhq_service.fetch_actions(symbol)
        return {
            "provider": provider_id,
            "data_mode": "CORPORATE_ACTIONS",
            "symbol": symbol.upper(),
            "received_at": datetime.now(timezone.utc),
            "rows": rows,
        }
    except TejHqError as error:
        raise _tejhq_http_error(error) from error


@router.get(
    "/api/v1/app/research/market-data/status",
    dependencies=[Depends(require_app_session)],
)
async def app_research_eod_status():
    try:
        provider_id, status = await tejhq_service.fetch_status()
        return {
            "provider": provider_id,
            "data_mode": "END_OF_DAY",
            "received_at": datetime.now(timezone.utc),
            "status": status,
        }
    except TejHqError as error:
        raise _tejhq_http_error(error) from error


class NseMcpToolCallRequest(BaseModel):
    server: Literal["bhavcopy", "cmmkt"]
    tool: str = Field(min_length=1, max_length=128)
    arguments: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def bound_arguments(self):
        size = len(json.dumps(self.arguments, ensure_ascii=True, separators=(",", ":")).encode("utf-8"))
        if size > 32 * 1024:
            raise ValueError("NSE MCP arguments must not exceed 32 KB")
        return self


@router.get(
    "/api/v1/app/research/nse-mcp/providers",
    dependencies=[Depends(require_app_session)],
)
def app_nse_mcp_providers():
    return {"providers": nse_mcp_service.provider_catalog()}


@router.get(
    "/api/v1/app/research/nse-mcp/tools",
    dependencies=[Depends(require_app_session)],
)
async def app_nse_mcp_tools(server: Literal["bhavcopy", "cmmkt", "both"] = "both"):
    try:
        catalog, failures = await nse_mcp_service.discover_tools(server)
        if not catalog:
            raise HTTPException(status_code=502, detail="NSE MCP tool discovery failed")
        return {
            "provider": "official_nse_mcp",
            "catalog": catalog,
            "failures": failures,
            "received_at": datetime.now(timezone.utc),
            "usage": "Private educational research only; no raw data retention or AI training",
        }
    except NseMcpError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.post(
    "/api/v1/app/research/nse-mcp/call",
    dependencies=[Depends(require_app_session), Depends(nse_tool_rate_limit)],
)
async def app_nse_mcp_call(request: NseMcpToolCallRequest):
    try:
        result = await nse_mcp_service.invoke_tool(
            request.server, request.tool, request.arguments
        )
        mode = "END_OF_DAY" if request.server == "bhavcopy" else "DELAYED"
        return {
            "provider": f"nse_mcp_{request.server}",
            "data_mode": mode,
            "expected_delay": None if mode == "END_OF_DAY" else "approximately 1–3 minutes",
            "received_at": datetime.now(timezone.utc),
            "source_timestamp": extract_source_timestamp(result),
            "source_timestamp_note": "Only an explicitly supplied source timestamp is reported; retrieval time is not a market timestamp.",
            "result": result,
            "retention": "Response is returned on demand and is not persisted by this endpoint.",
            "disclaimer": "Informational and educational research only; not for trading, redistribution, commercial use, or AI/ML training.",
        }
    except NseMcpError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
