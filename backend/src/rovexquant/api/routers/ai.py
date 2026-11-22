"""AI gateway routes; provider selection and execution remain in the AI gateway."""
from __future__ import annotations

import math
from datetime import date, datetime, timezone
from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field, model_validator

from rovexquant.ai.gateway import GatewayError, authorize_gateway, complete_chat, configured_providers
from rovexquant.market_data import tejhq_service
from rovexquant.research.contracts import IndicatorRequest
from rovexquant.research.service import calculate_indicators
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import ai_request_rate_limit

router = APIRouter()


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


class AIStrategyBriefRequest(BaseModel):
    provider_id: str = Field(min_length=1, max_length=40)
    model: str = Field(min_length=1, max_length=160)
    exchange: Literal["NSE", "BSE"] = "NSE"
    symbol: str = Field(min_length=1, max_length=30)
    from_date: date
    to_date: date
    strategy_context: str = Field(min_length=1, max_length=1500)
    credential_id: str | None = Field(default=None, min_length=1, max_length=80)
    fallback_providers: list[str] = Field(default_factory=list, max_length=3)

    @model_validator(mode="after")
    def validate_date_range(self):
        if self.from_date > self.to_date:
            raise ValueError("from_date must be on or before to_date")
        if (self.to_date - self.from_date).days > 6200:
            raise ValueError("Requested history range exceeds the supported EOD window")
        return self


def _last_finite(values: list[Any]) -> float | None:
    for value in reversed(values):
        if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value):
            return float(value)
    return None


def _build_eod_evidence(bars: list[Any]) -> dict[str, Any]:
    closes = [item.close for item in bars]
    highs = [item.high for item in bars]
    lows = [item.low for item in bars]
    volumes = [item.volume for item in bars]
    indicators = calculate_indicators(IndicatorRequest(close=closes, high=highs, low=lows))
    sma20 = _last_finite(indicators["sma"])
    sma50 = _last_finite(indicators["ema"]) if len(closes) < 50 else _last_finite(
        calculate_indicators(IndicatorRequest(close=closes, high=highs, low=lows, sma_period=50))["sma"]
    )
    rsi14 = _last_finite(indicators["rsi"])
    atr14 = _last_finite(indicators["atr"])
    adx14 = _last_finite(indicators["adx"])
    prior_highs = highs[-21:-1] if len(highs) >= 21 else []
    prior_lows = lows[-21:-1] if len(lows) >= 21 else []
    donchian_high = max(prior_highs) if prior_highs else None
    donchian_low = min(prior_lows) if prior_lows else None
    average_volume_20 = (sum(volumes[-21:-1]) / 20) if len(volumes) >= 21 else None
    relative_volume = (
        volumes[-1] / average_volume_20
        if average_volume_20 is not None and average_volume_20 > 0 else None
    )
    return {
        "bar_count": len(bars),
        "first_trading_date": bars[0].date.isoformat(),
        "latest_trading_date": bars[-1].date.isoformat(),
        "latest_close": closes[-1],
        "latest_volume": volumes[-1],
        "sma20": sma20,
        "sma50": sma50,
        "rsi14": rsi14,
        "atr14": atr14,
        "adx14": adx14,
        "donchian20_prior_high": donchian_high,
        "donchian20_prior_low": donchian_low,
        "latest_close_vs_donchian": (
            "ABOVE_PRIOR_20_DAY_HIGH" if donchian_high is not None and closes[-1] > donchian_high
            else "BELOW_PRIOR_20_DAY_LOW" if donchian_low is not None and closes[-1] < donchian_low
            else "INSIDE_PRIOR_20_DAY_RANGE" if donchian_high is not None
            else "INSUFFICIENT_HISTORY"
        ),
        "relative_volume_vs_prior_20_sessions": relative_volume,
        "return_21_sessions_fraction": (
            closes[-1] / closes[-22] - 1 if len(closes) >= 22 and closes[-22] > 0 else None
        ),
        "return_63_sessions_fraction": (
            closes[-1] / closes[-64] - 1 if len(closes) >= 64 and closes[-64] > 0 else None
        ),
        "trend_context": (
            "CLOSE_ABOVE_SMA20_AND_SMA50" if sma20 is not None and sma50 is not None
            and closes[-1] > sma20 > sma50
            else "CLOSE_BELOW_SMA20_AND_SMA50" if sma20 is not None and sma50 is not None
            and closes[-1] < sma20 < sma50
            else "MIXED_OR_INSUFFICIENT"
        ),
    }


@router.get("/api/v1/ai/providers")
def ai_providers(x_quant_ai_gateway_key: str | None = Header(default=None)):
    """Legacy server-to-server provider discovery; never expose this key to mobile clients."""
    try:
        authorize_gateway(x_quant_ai_gateway_key)
        return {"providers": configured_providers()}
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.post("/api/v1/ai/chat")
async def ai_chat(
    request: AIChatRequest,
    x_quant_ai_gateway_key: str | None = Header(default=None),
):
    """Legacy server-to-server completion route."""
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


@router.get(
    "/api/v1/app/ai/providers",
    dependencies=[Depends(require_app_session)],
)
def app_ai_providers():
    """Mobile-safe provider catalogue. No provider or gateway secret leaves the server."""
    return {
        "providers": [
            {
                "provider_id": item["provider_id"],
                "configured": item["configured"],
                "credential_ids": item["credential_ids"],
            }
            for item in configured_providers()
        ]
    }


@router.post(
    "/api/v1/app/ai/chat",
    dependencies=[Depends(require_app_session), Depends(ai_request_rate_limit)],
)
async def app_ai_chat(request: AIChatRequest):
    """Mobile AI access is authorized by the short-lived Quant app session."""
    try:
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


@router.post(
    "/api/v1/app/ai/strategy-brief",
    dependencies=[Depends(require_app_session), Depends(ai_request_rate_limit)],
)
async def app_ai_strategy_brief(request: AIStrategyBriefRequest):
    """Last-resort, verified EOD data recovery followed by an evidence-bounded AI brief.

    TejHQ is used only for its documented keyless EOD OHLCV endpoint. The AI model
    receives a compact deterministic summary, not the raw market-data response.
    Intraday/scalping data is never inferred from EOD bars.
    """
    try:
        provider_id, bars = await tejhq_service.fetch_ohlcv(
            request.exchange,
            request.symbol,
            request.from_date,
            request.to_date,
            adjusted=False,
        )
        if len(bars) < 30:
            raise HTTPException(
                status_code=422,
                detail=f"Only {len(bars)} validated EOD bars were available; at least 30 are required for a useful research brief.",
            )
        evidence = _build_eod_evidence(bars)
        system_prompt = (
            "You are Quant's evidence-bounded market research assistant. "
            "Use only the supplied validated end-of-day (EOD) evidence. "
            "Do not invent prices, candles, volume, indicators, dates, corporate actions, "
            "provider entitlements, or backtest results. Do not call the output a live signal. "
            "EOD data is unsuitable for scalping or intraday decisions. "
            "Explain what the metrics support, what they do not support, key failure modes, "
            "and what additional tests are required. Never issue a buy/sell instruction or "
            "guarantee returns. If evidence is insufficient, say so plainly."
        )
        user_evidence = {
            "task": "Review the proposed strategy idea against this retrieved EOD evidence.",
            "exchange": request.exchange,
            "symbol": request.symbol.upper(),
            "requested_period": {
                "from": request.from_date.isoformat(),
                "to": request.to_date.isoformat(),
            },
            "strategy_context": request.strategy_context,
            "source": provider_id,
            "data_mode": "END_OF_DAY",
            "evidence": evidence,
            "interpretation_constraints": [
                "Daily bars only; never describe as intraday or live.",
                "Indicators and breakout context were calculated deterministically by Quant.",
                "A research brief is not a validated strategy, backtest, or trade signal.",
                "Do not infer missing fundamentals, corporate actions, sector breadth, or market depth.",
            ],
        }
        completion = await complete_chat(
            provider_id=request.provider_id,
            model=request.model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": str(user_evidence)},
            ],
            max_tokens=1400,
            temperature=0.1,
            credential_id=request.credential_id,
            fallback_providers=request.fallback_providers,
        )
        return {
            "provider": provider_id,
            "data_mode": "END_OF_DAY",
            "exchange": request.exchange,
            "symbol": request.symbol.upper(),
            "requested_from": request.from_date.isoformat(),
            "requested_to": request.to_date.isoformat(),
            "first_trading_date": evidence["first_trading_date"],
            "latest_trading_date": evidence["latest_trading_date"],
            "received_at": datetime.now(timezone.utc).isoformat(),
            "bar_count": len(bars),
            "evidence": evidence,
            "bars": [
                {
                    "date": item.date.isoformat(),
                    "open": item.open,
                    "high": item.high,
                    "low": item.low,
                    "close": item.close,
                    "volume": item.volume,
                }
                for item in bars
            ],
            "ai": completion,
            "data_rights": (
                "Private personal research only. Do not redistribute raw data or expose it to other users. "
                "Exchange terms may independently apply; this endpoint does not grant exchange display rights."
            ),
            "limitations": [
                "EOD daily bars only; not suitable for intraday or scalping strategies.",
                "No licensed live feed, order book, market depth, or guaranteed accuracy.",
                "AI commentary is not a trade signal or investment advice.",
                "If this application is offered to others or used commercially, obtain the required provider and exchange rights first.",
            ],
        }
    except TejHqError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
