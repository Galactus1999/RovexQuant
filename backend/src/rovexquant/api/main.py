from contextlib import asynccontextmanager
from dataclasses import asdict
import asyncio
from datetime import datetime, timezone
import hmac
import os

from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.openapi.utils import get_openapi
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from pydantic import BaseModel, Field, model_validator

from rovexquant.ai.gateway import GatewayError, authorize_gateway, complete_chat, configured_providers
from rovexquant.market_data.providers import QuoteInstrument
from rovexquant.market_data.instruments import InstrumentMasterError, get_nse_index_instruments
from rovexquant.market_data.service import (
    MarketGatewayError, authorize_market_gateway, fetch_quotes, provider_status,
)
from rovexquant.auth import AppAuthError, bearer_claims, issue_app_session
from rovexquant.security.request_controls import (
    RequestBodyLimitMiddleware, login_rate_limit,
)
from rovexquant.security.request_context import RequestContextMiddleware, request_metrics
from rovexquant.security.errors import http_exception_handler, validation_exception_handler
from rovexquant.research.router import router as research_router
from rovexquant.api.research_data import router as research_data_router
from rovexquant.market_data.streaming import StreamError, stream_upstox_market_data
from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError, store_upstox_analytics_token, upstox_analytics_status,
    validate_storage_configuration, validate_postgres_schema, validate_storage_readiness,
)
from rovexquant.strategies.registry import default_registry



@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Hosted Docker defaults to PostgreSQL and must not silently start with SQLite.
    validate_storage_configuration()
    validate_postgres_schema()
    yield


app = FastAPI(
    title="Quant API",
    version="0.5.0",
    description="Evidence-based market research. No order execution.",
    lifespan=lifespan,
)
app.add_middleware(RequestBodyLimitMiddleware, max_body_size=2 * 1024 * 1024)
app.add_middleware(RequestContextMiddleware)
app.add_exception_handler(StarletteHTTPException, http_exception_handler)
app.add_exception_handler(RequestValidationError, validation_exception_handler)
app.include_router(research_router)
app.include_router(research_data_router)

def _quant_openapi():
    """Publish mobile app authentication as a Swagger HTTP Bearer security scheme."""
    if app.openapi_schema:
        return app.openapi_schema

    schema = get_openapi(
        title=app.title,
        version=app.version,
        description=app.description,
        routes=app.routes,
    )
    components = schema.setdefault("components", {})
    schemes = components.setdefault("securitySchemes", {})
    schemes["QuantAppBearer"] = {
        "type": "http",
        "scheme": "bearer",
        "bearerFormat": "JWT",
        "description": "Use the short-lived token returned by POST /api/v1/auth/session.",
    }

    for path, path_item in schema.get("paths", {}).items():
        if not path.startswith("/api/v1/app/") and path not in {
            "/api/v1/research/indicators", "/api/v1/research/backtest",
        }:
            continue
        for operation in path_item.values():
            if not isinstance(operation, dict) or "responses" not in operation:
                continue
            operation["security"] = [{"QuantAppBearer": []}]
            operation["parameters"] = [
                parameter
                for parameter in operation.get("parameters", [])
                if not (
                    parameter.get("in") == "header"
                    and parameter.get("name", "").lower() == "authorization"
                )
            ]

    app.openapi_schema = schema
    return app.openapi_schema


app.openapi = _quant_openapi




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


@app.post("/api/v1/auth/session", dependencies=[Depends(login_rate_limit)])
def create_app_session(request: AppLoginRequest):
    try:
        token, expires_in = issue_app_session(request.username, request.password)
        return {"access_token": token, "token_type": "Bearer", "expires_in": expires_in}
    except AppAuthError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/app/auth/session")
def validate_app_session(authorization: str | None = Header(default=None)):
    """Validate a previously issued mobile session without exposing its bearer token."""
    try:
        claims = bearer_claims(authorization)
        return {"authenticated": True, "expires_at": claims["exp"]}
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
@app.get("/api/v1/health/live")
def health():
    """Liveness only; optional providers are not required for process health."""
    return {"status": "ok", "application": "Quant", "mode": "research-only"}


@app.get("/api/v1/health/metrics")
def health_metrics(x_quant_admin_key: str | None = Header(default=None)):
    """Expose real process-local HTTP metrics only to the configured admin."""
    try:
        authorize_quant_admin(x_quant_admin_key)
        return request_metrics.snapshot()
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@app.get("/api/v1/health/ready")
def readiness():
    """Readiness for mandatory credential storage; never returns secret/config values."""
    try:
        validate_storage_readiness()
        return {"status": "ready", "application": "Quant"}
    except UpstoxCredentialError as error:
        raise HTTPException(
            status_code=503,
            detail="Required credential storage is unavailable or its schema is not migrated",
        ) from error


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
