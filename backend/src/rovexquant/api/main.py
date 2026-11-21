from dataclasses import asdict
import asyncio
from datetime import date, datetime, timezone
import hmac
import os
from typing import Any, Literal

from fastapi import FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, model_validator

from rovexquant.ai.gateway import GatewayError, authorize_gateway, complete_chat, configured_providers
from rovexquant.backtesting.engine import BacktestBar, BacktestConfig, run_long_only_backtest
from rovexquant.indicators.momentum import rsi
from rovexquant.indicators.trend import adx, macd
from rovexquant.indicators.volatility import atr
from rovexquant.market_data.providers import QuoteInstrument
from rovexquant.market_data.instruments import InstrumentMasterError, get_nse_index_instruments
from rovexquant.market_data.service import (
    MarketGatewayError, authorize_market_gateway, fetch_quotes, provider_status,
)
from rovexquant.auth import AppAuthError, bearer_claims, issue_app_session
from rovexquant.market_data.streaming import StreamError, stream_upstox_market_data
from rovexquant.market_data.tejhq import TejHqEodProvider, TejHqError
from rovexquant.market_data.nse_mcp import NseMcpClient, NseMcpError, safe_tool_catalog, provider_status as nse_mcp_provider_status
from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError, store_upstox_analytics_token, upstox_analytics_status,
)
from rovexquant.strategies.registry import default_registry

app = FastAPI(title="Quant API", version="0.5.0", description="Evidence-based market research. No order execution.")


def authorize_quant_admin(supplied_key: str | None) -> None:
    expected = os.getenv("QUANT_ADMIN_GATEWAY_TOKEN", "").strip()
    if not expected:
        raise UpstoxCredentialError("Quant admin endpoints are disabled until configured", 503)
    if not supplied_key or not hmac.compare_digest(expected, supplied_key):
        raise UpstoxCredentialError("Invalid Quant admin credential", 401)


class MarketInstrumentInput(BaseModel):
    symbol: str = Field(min_length=1, max_length=80)
    upstox_instrument_key: str | None = Field(default=None, max_length=120)
    dhan_exchange_segment: str | None = Field(default=None, max_length=30)
    dhan_security_id: str | None = Field(default=None, max_length=30)


class MarketQuotesRequest(BaseModel):
    instruments: list[MarketInstrumentInput] = Field(min_length=1, max_length=500)
    provider_order: list[str] = Field(default_factory=lambda: ["upstox", "dhan"], min_length=1, max_length=2)

    @model_validator(mode="after")
    def unique_symbols(self):
        symbols = [item.symbol for item in self.instruments]
        if len(symbols) != len(set(symbols)):
            raise ValueError("Instrument symbols must be unique within a quote request")
        if len(self.provider_order) != len(set(self.provider_order)):
            raise ValueError("Provider order must not contain duplicates")
        return self



class AppLoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=1, max_length=256)


@app.post("/api/v1/auth/session")
def create_app_session(request: AppLoginRequest):
    try:
        token, expires_in = issue_app_session(request.username, request.password)
        return {"access_token": token, "token_type": "Bearer", "expires_in": expires_in}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/app/market/providers")
def app_market_providers(authorization: str | None = Header(default=None)):
    try:
        bearer_claims(authorization)
        return {"providers": provider_status()}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error



@app.get("/api/v1/app/market/index-instruments")
async def app_market_index_instruments(authorization: str | None = Header(default=None)):
    try:
        bearer_claims(authorization)
        instruments = await get_nse_index_instruments()
        return {"provider": "upstox_instrument_master", "instruments": instruments}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except InstrumentMasterError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.post("/api/v1/app/market/quotes")
async def app_market_quotes(
    request: MarketQuotesRequest,
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
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
            "provider": provider, "fallback_used": fallback_used,
            "received_at": datetime.now(timezone.utc), "complete": len(quoted_symbols) == len(instruments),
            "missing_symbols": [item.symbol for item in instruments if item.symbol not in quoted_symbols],
            "quotes": [asdict(item) for item in quotes],
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except MarketGatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.websocket("/api/v1/app/market/stream")
async def app_market_stream(websocket: WebSocket):
    try:
        authorization = websocket.headers.get("authorization")
        bearer_claims(authorization)
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
            raise StreamError("instrument_keys must be a list of NSE index keys")
        await stream_upstox_market_data(websocket, keys, mode)
    except (StreamError, asyncio.TimeoutError) as error:
        await websocket.send_json({"type": "error", "message": str(error)})
        await websocket.close(code=4400, reason="Invalid or unavailable market stream")
    except WebSocketDisconnect:
        return
    except Exception:
        await websocket.send_json({"type": "error", "message": "Market stream disconnected"})
        await websocket.close(code=1011, reason="Market stream unavailable")


class IndicatorRequest(BaseModel):
    close: list[float] = Field(min_length=1)
    high: list[float] | None = None
    low: list[float] | None = None
    rsi_period: int = Field(default=14, ge=2, le=500)
    atr_period: int = Field(default=14, ge=2, le=500)
    adx_period: int = Field(default=14, ge=2, le=500)


class BacktestBarInput(BaseModel):
    timestamp: datetime
    open: float = Field(gt=0)
    high: float = Field(gt=0)
    low: float = Field(gt=0)
    close: float = Field(gt=0)


class BacktestRequest(BaseModel):
    bars: list[BacktestBarInput] = Field(min_length=2, max_length=100000)
    signals: list[int]
    initial_capital: float = Field(default=100000, gt=0)
    risk_fraction: float = Field(default=0.01, gt=0, le=0.05)
    max_position_fraction: float = Field(default=0.25, gt=0, le=1)
    stop_fraction: float = Field(default=0.02, gt=0, lt=1)
    commission_bps: float = Field(default=5, ge=0)
    slippage_bps: float = Field(default=5, ge=0)


class AIMessage(BaseModel):
    role: str
    content: str = Field(min_length=1, max_length=12000)


class AIChatRequest(BaseModel):
    provider_id: str
    model: str = Field(min_length=1, max_length=160)
    messages: list[AIMessage] = Field(min_length=1, max_length=32)
    credential_id: str | None = Field(default=None, min_length=1, max_length=80)
    fallback_providers: list[str] = Field(default_factory=list, max_length=3)
    max_tokens: int = Field(default=1200, ge=1, le=4096)
    temperature: float = Field(default=0.2, ge=0, le=2)


class UpstoxAnalyticsTokenInput(BaseModel):
    token: str = Field(min_length=20, max_length=4096)


@app.post("/api/v1/integrations/upstox/analytics-token")
def save_upstox_analytics_token(
    request: UpstoxAnalyticsTokenInput,
    x_quant_admin_key: str | None = Header(default=None),
):
    try:
        authorize_quant_admin(x_quant_admin_key)
        store_upstox_analytics_token(request.token)
        return {
            "status": "connected",
            "provider": "upstox",
            "credential_type": "analytics_read_only",
            "message": "Token encrypted and stored on the Quant backend; it is never returned",
        }
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/integrations/upstox/status")
def upstox_status(x_quant_admin_key: str | None = Header(default=None)):
    try:
        authorize_quant_admin(x_quant_admin_key)
        return upstox_analytics_status()
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/market/providers")
def market_providers(x_quant_market_gateway_key: str | None = Header(default=None)):
    try:
        authorize_market_gateway(x_quant_market_gateway_key)
        return {"providers": provider_status()}
    except MarketGatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.post("/api/v1/market/quotes")
async def market_quotes(
    request: MarketQuotesRequest,
    x_quant_market_gateway_key: str | None = Header(default=None),
):
    try:
        authorize_market_gateway(x_quant_market_gateway_key)
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
    except MarketGatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/health")
def health():
    return {"status": "ok", "application": "Quant", "mode": "research-only"}


@app.get("/api/v1/strategies")
def strategies():
    return [
        {"strategy_id": item.metadata.strategy_id, "name": item.metadata.name,
         "version": item.metadata.version, "horizon": item.metadata.horizon,
         "description": item.metadata.description}
        for item in default_registry().all()
    ]


@app.get("/api/v1/ai/providers")
def ai_providers(x_quant_ai_gateway_key: str | None = Header(default=None)):
    try:
        authorize_gateway(x_quant_ai_gateway_key)
        return {"providers": configured_providers()}
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.post("/api/v1/ai/chat")
async def ai_chat(request: AIChatRequest, x_quant_ai_gateway_key: str | None = Header(default=None)):
    try:
        authorize_gateway(x_quant_ai_gateway_key)
        return await complete_chat(
            provider_id=request.provider_id,
            model=request.model,
            messages=[message.model_dump() for message in request.messages],
            max_tokens=request.max_tokens,
            temperature=request.temperature,
            credential_id=request.credential_id,
            fallback_providers=request.fallback_providers,
        )
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.post("/api/v1/research/indicators")
def calculate_indicators(request: IndicatorRequest):
    try:
        result = {"rsi": rsi(request.close, request.rsi_period), "macd": macd(request.close)}
        if request.high is not None or request.low is not None:
            if request.high is None or request.low is None:
                raise ValueError("high and low must be supplied together")
            if not (len(request.high) == len(request.low) == len(request.close)):
                raise ValueError("OHLC arrays must have equal lengths")
            result["atr"] = atr(request.high, request.low, request.close, request.atr_period)
            result["adx"] = adx(request.high, request.low, request.close, request.adx_period)
        return result
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/api/v1/research/backtest")
def backtest(request: BacktestRequest):
    if len(request.bars) != len(request.signals):
        raise HTTPException(status_code=422, detail="bars and signals must have equal lengths")
    try:
        config = BacktestConfig(
            initial_capital=request.initial_capital, risk_fraction=request.risk_fraction,
            max_position_fraction=request.max_position_fraction, stop_fraction=request.stop_fraction,
            commission_bps=request.commission_bps, slippage_bps=request.slippage_bps,
        )
        bars = [BacktestBar(**bar.model_dump()) for bar in request.bars]
        result = run_long_only_backtest(bars, request.signals, config)
        return {
            "initial_capital": result.initial_capital, "final_equity": result.final_equity,
            "total_return_fraction": result.total_return_fraction,
            "max_drawdown_fraction": result.max_drawdown_fraction, "win_rate": result.win_rate,
            "profit_factor": result.profit_factor, "trade_count": result.trade_count,
            "trades": [{
                "entry_time": trade.entry_time, "exit_time": trade.exit_time,
                "entry_price": trade.entry_price, "exit_price": trade.exit_price,
                "quantity": trade.quantity, "net_pnl": trade.net_pnl,
                "return_fraction": trade.return_fraction, "exit_reason": trade.exit_reason,
            } for trade in result.trades],
        }
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


def _tejhq_or_http_error(error: TejHqError) -> HTTPException:
    return HTTPException(status_code=error.status_code, detail=str(error))


@app.get("/api/v1/app/research/market-data/providers")
def app_research_data_providers(authorization: str | None = Header(default=None)):
    try:
        bearer_claims(authorization)
        return {"providers": [TejHqEodProvider.from_environment().safe_status()]}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/app/research/market-data/ohlcv/{exchange}/{symbol}")
async def app_research_eod_ohlcv(
    exchange: str,
    symbol: str,
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    adjusted: bool = False,
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
        provider = TejHqEodProvider.from_environment()
        bars = await provider.get_ohlcv(exchange, symbol, from_date, to_date, adjusted=adjusted)
        return {
            "provider": provider.provider_id,
            "data_mode": "END_OF_DAY",
            "adjusted": adjusted,
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "from": from_date,
            "to": to_date,
            "received_at": datetime.now(timezone.utc),
            "timestamp_semantics": "Trading date only; not an intraday or live timestamp",
            "bars": [asdict(item) for item in bars],
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except TejHqError as error:
        raise _tejhq_or_http_error(error) from error


@app.get("/api/v1/app/research/market-data/delivery/{exchange}/{symbol}")
async def app_research_eod_delivery(
    exchange: str,
    symbol: str,
    from_date: date = Query(..., alias="from"),
    to_date: date = Query(..., alias="to"),
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
        provider = TejHqEodProvider.from_environment()
        rows = await provider.get_delivery(exchange, symbol, from_date, to_date)
        return {
            "provider": provider.provider_id,
            "data_mode": "END_OF_DAY",
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "received_at": datetime.now(timezone.utc),
            "rows": rows,
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except TejHqError as error:
        raise _tejhq_or_http_error(error) from error


@app.get("/api/v1/app/research/market-data/snapshot/{exchange}")
async def app_research_eod_snapshot(
    exchange: str,
    trading_date: date,
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
        provider = TejHqEodProvider.from_environment()
        rows = await provider.get_snapshot(exchange, trading_date)
        return {
            "provider": provider.provider_id,
            "data_mode": "END_OF_DAY",
            "exchange": exchange.upper(),
            "trading_date": trading_date,
            "received_at": datetime.now(timezone.utc),
            "rows": rows,
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except TejHqError as error:
        raise _tejhq_or_http_error(error) from error


@app.get("/api/v1/app/research/market-data/actions/{symbol}")
async def app_research_corporate_actions(
    symbol: str,
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
        provider = TejHqEodProvider.from_environment()
        rows = await provider.get_actions(symbol)
        return {
            "provider": provider.provider_id,
            "data_mode": "CORPORATE_ACTIONS",
            "symbol": symbol.upper(),
            "received_at": datetime.now(timezone.utc),
            "rows": rows,
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except TejHqError as error:
        raise _tejhq_or_http_error(error) from error


@app.get("/api/v1/app/research/market-data/status")
async def app_research_eod_status(authorization: str | None = Header(default=None)):
    try:
        bearer_claims(authorization)
        provider = TejHqEodProvider.from_environment()
        status = await provider.get_status()
        return {
            "provider": provider.provider_id,
            "data_mode": "END_OF_DAY",
            "received_at": datetime.now(timezone.utc),
            "status": status,
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except TejHqError as error:
        raise _tejhq_or_http_error(error) from error



class NseMcpToolCallRequest(BaseModel):
    server: Literal["bhavcopy", "cmmkt"]
    tool: str = Field(min_length=1, max_length=128)
    arguments: dict[str, Any] = Field(default_factory=dict)


@app.get("/api/v1/app/research/nse-mcp/providers")
def app_nse_mcp_providers(authorization: str | None = Header(default=None)):
    try:
        bearer_claims(authorization)
        return {"providers": nse_mcp_provider_status()}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/app/research/nse-mcp/tools")
async def app_nse_mcp_tools(
    server: Literal["bhavcopy", "cmmkt", "both"] = "both",
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
        selected = ["bhavcopy", "cmmkt"] if server == "both" else [server]
        catalog: dict[str, Any] = {}
        failures: dict[str, str] = {}
        for server_name in selected:
            try:
                async with NseMcpClient(server_name) as client:
                    catalog[server_name] = safe_tool_catalog(await client.list_tools())
            except NseMcpError as error:
                failures[server_name] = str(error)
        if not catalog:
            raise HTTPException(status_code=502, detail={"message": "NSE MCP tool discovery failed", "servers": failures})
        return {
            "provider": "official_nse_mcp",
            "catalog": catalog,
            "failures": failures,
            "received_at": datetime.now(timezone.utc),
            "usage": "Private educational research only; no raw data retention or AI training",
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.post("/api/v1/app/research/nse-mcp/call")
async def app_nse_mcp_call(
    request: NseMcpToolCallRequest,
    authorization: str | None = Header(default=None),
):
    try:
        bearer_claims(authorization)
        async with NseMcpClient(request.server) as client:
            result = await client.call_tool(request.tool, request.arguments)
        mode = "END_OF_DAY" if request.server == "bhavcopy" else "DELAYED"
        return {
            "provider": f"nse_mcp_{request.server}",
            "data_mode": mode,
            "expected_delay": None if mode == "END_OF_DAY" else "approximately 1–3 minutes",
            "received_at": datetime.now(timezone.utc),
            "source_timestamp": None,
            "source_timestamp_note": "Use a timestamp included in the returned NSE tool payload; retrieval time is not a market timestamp.",
            "result": result,
            "retention": "Response is returned on demand and is not persisted by this endpoint.",
            "disclaimer": "Informational and educational research only; not for trading, redistribution, commercial use, or AI/ML training.",
        }
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except NseMcpError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
