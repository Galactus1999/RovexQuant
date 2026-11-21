from datetime import datetime, timezone
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from rovexquant.ai.gateway import GatewayError, authorize_gateway, complete_chat, configured_providers
from rovexquant.backtesting.engine import BacktestBar, BacktestConfig, run_long_only_backtest
from rovexquant.indicators.momentum import rsi
from rovexquant.indicators.trend import adx, macd
from rovexquant.indicators.volatility import atr
from rovexquant.strategies.registry import default_registry

app = FastAPI(title="Quant API", version="0.3.0", description="Evidence-based market research. No order execution.")

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
