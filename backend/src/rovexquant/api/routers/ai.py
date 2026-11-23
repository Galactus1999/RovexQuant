"""AI gateway routes; provider selection and execution remain in the AI gateway."""
from __future__ import annotations

import json
import math
from dataclasses import replace
from datetime import date, datetime, timezone
from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field, SecretStr, model_validator

from rovexquant.ai.gateway import GatewayError, authorize_gateway, complete_chat, configured_providers, configured_task_routes, resolve_task_route
from rovexquant.ai.providers import SUPPORTED
from rovexquant.market_data import tejhq_service
from rovexquant.market_data.tejhq_service import TejHqError
from rovexquant.market_data.eod_store import EodStoreError
from rovexquant.ai.research_archive import (
    ai_token_budgets_enabled, estimate_reserved_tokens, reserve_ai_research_run,
    finish_ai_research_run, fail_ai_research_run,
    list_ai_research_runs, get_ai_research_run, get_ai_token_budget_status,
)
from rovexquant.research.contracts import IndicatorRequest
from rovexquant.research.service import calculate_indicators
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import ai_request_rate_limit, provider_credential_rate_limit
from rovexquant.market_data.upstox_credentials import (
    UpstoxCredentialError,
    delete_ai_provider_api_key,
    store_ai_provider_api_key,
)

router = APIRouter()


class AIMessage(BaseModel):
    role: str
    content: str = Field(min_length=1, max_length=12000)


class AIChatRequest(BaseModel):
    provider_id: str | None = None
    model: str | None = Field(default=None, min_length=1, max_length=160)
    task_type: Literal["chat", "research", "macro_analysis", "filing_extraction", "claim_verification", "summarization"] = "chat"
    messages: list[AIMessage] = Field(min_length=1, max_length=32)
    credential_id: str | None = Field(default=None, min_length=1, max_length=80)
    fallback_providers: list[str] = Field(default_factory=list, max_length=3)
    max_tokens: int = Field(default=1200, ge=1, le=4096)
    temperature: float = Field(default=0.2, ge=0, le=2)


class AIProviderCredentialInput(BaseModel):
    api_key: SecretStr




class AIStrategyBriefRequest(BaseModel):
    provider_id: str | None = Field(default=None, max_length=40)
    model: str | None = Field(default=None, max_length=160)
    exchange: Literal["NSE", "BSE"] = "NSE"
    symbol: str = Field(min_length=1, max_length=30)
    from_date: date
    to_date: date
    strategy_context: str = Field(min_length=1, max_length=1500)
    credential_id: str | None = Field(default=None, min_length=1, max_length=80)
    fallback_providers: list[str] = Field(default_factory=list, max_length=3)
    automatic_task_routing: bool = False
    max_tokens: int = Field(default=1400, ge=1, le=4096)

    @model_validator(mode="after")
    def validate_date_range(self):
        if self.from_date > self.to_date:
            raise ValueError("from_date must be on or before to_date")
        if (self.to_date - self.from_date).days > 6200:
            raise ValueError("Requested history range exceeds the supported EOD window")
        return self

    @model_validator(mode="after")
    def validate_provider_model_pair(self):
        if bool(self.provider_id) != bool(self.model):
            raise ValueError("provider_id and model must be supplied together, or both omitted for task routing")
        if self.automatic_task_routing and (self.provider_id or self.model):
            raise ValueError("automatic_task_routing cannot be combined with a manual provider/model")
        return self


def _last_finite(values: list[Any]) -> float | None:
    for value in reversed(values):
        if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value):
            return float(value)
    return None


def _apply_corporate_action_adjustment(bars: list[Any]) -> list[Any]:
    adjusted_bars: list[Any] = []
    for item in bars:
        factor = item.adjustment_factor
        adjusted_close = item.adjusted_close
        if (
            not isinstance(factor, (int, float))
            or not math.isfinite(factor)
            or factor <= 0
            or not isinstance(adjusted_close, (int, float))
            or not math.isfinite(adjusted_close)
            or adjusted_close <= 0
        ):
            raise TejHqError("TejHQ adjusted bar is missing a valid corporate-action factor", 502)
        expected_adjusted_close = item.close * factor
        if not math.isclose(adjusted_close, expected_adjusted_close, rel_tol=1e-5, abs_tol=1e-6):
            raise TejHqError("TejHQ adjusted close does not reconcile with its cumulative factor", 502)
        opening = item.open * factor
        high = item.high * factor
        low = item.low * factor
        if low > min(opening, adjusted_close) or high < max(opening, adjusted_close) or low > high:
            raise TejHqError("Adjusted EOD OHLC values are inconsistent", 502)
        adjusted_bars.append(
            replace(item, open=opening, high=high, low=low, close=adjusted_close)
        )
    return adjusted_bars


def _build_eod_evidence(bars: list[Any]) -> dict[str, Any]:
    closes = [item.close for item in bars]
    highs = [item.high for item in bars]
    lows = [item.low for item in bars]
    indicators = calculate_indicators(IndicatorRequest(close=closes, high=highs, low=lows))
    sma20 = _last_finite(indicators["sma"])
    sma50 = _last_finite(
        calculate_indicators(IndicatorRequest(close=closes, high=highs, low=lows, sma_period=50))["sma"]
    ) if len(closes) >= 50 else None
    rsi14 = _last_finite(indicators["rsi"])
    atr14 = _last_finite(indicators["atr"])
    adx14 = _last_finite(indicators["adx"])
    prior_highs = highs[-21:-1] if len(highs) >= 21 else []
    prior_lows = lows[-21:-1] if len(lows) >= 21 else []
    donchian_high = max(prior_highs) if prior_highs else None
    donchian_low = min(prior_lows) if prior_lows else None
    return {
        "bar_count": len(bars),
        "first_trading_date": bars[0].date.isoformat(),
        "latest_trading_date": bars[-1].date.isoformat(),
        "latest_close": closes[-1],
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
        "relative_volume_vs_prior_20_sessions": None,
        "volume_note": "Source volume is raw and unadjusted; excluded from relative-volume inference.",
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
                "default_model": item["default_model"],
                "fallback_model_configured": item["fallback_model_configured"],
            }
            for item in configured_providers()
        ]
    }




@router.get(
    "/api/v1/app/ai/budget",
    dependencies=[Depends(require_app_session)],
)
def app_ai_budget_status():
    try:
        return get_ai_token_budget_status()
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/ai/research/runs",
    dependencies=[Depends(require_app_session)],
)
def app_ai_research_runs(limit: int = 30, task_type: str | None = None):
    try:
        return list_ai_research_runs(limit=limit, task_type=task_type)
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/ai/research/runs/{run_id}",
    dependencies=[Depends(require_app_session)],
)
def app_ai_research_run(run_id: str):
    try:
        result = get_ai_research_run(run_id)
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    if result is None:
        raise HTTPException(status_code=404, detail="AI research run not found")
    return result


@router.get(
    "/api/v1/app/ai/task-routes",
    dependencies=[Depends(require_app_session)],
)
def app_ai_task_routes():
    """Return configured task-specific model routes without credential material."""
    try:
        return {"routes": configured_task_routes()}
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.put(
    "/api/v1/app/ai/providers/{provider_id}/credential",
    dependencies=[Depends(require_app_session), Depends(provider_credential_rate_limit)],
)
def save_app_ai_provider_credential(provider_id: str, request: AIProviderCredentialInput):
    """Store an owner-managed provider key encrypted; never return or log the key."""
    if provider_id not in SUPPORTED:
        raise HTTPException(status_code=404, detail="Unsupported AI provider")
    try:
        store_ai_provider_api_key(provider_id, request.api_key.get_secret_value())
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    return {
        "provider_id": provider_id,
        "configured": True,
        "credential_id": "app",
        "credential_storage": "encrypted_server_side",
        "message": "Credential stored. Provider acceptance is verified on the first AI request.",
    }


@router.delete(
    "/api/v1/app/ai/providers/{provider_id}/credential",
    dependencies=[Depends(require_app_session), Depends(provider_credential_rate_limit)],
)
def delete_app_ai_provider_credential(provider_id: str):
    """Remove the owner's encrypted provider key without exposing credential material."""
    if provider_id not in SUPPORTED:
        raise HTTPException(status_code=404, detail="Unsupported AI provider")
    try:
        deleted = delete_ai_provider_api_key(provider_id)
    except UpstoxCredentialError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    return {"provider_id": provider_id, "deleted": deleted}



@router.post(
    "/api/v1/app/ai/chat",
    dependencies=[Depends(require_app_session), Depends(ai_request_rate_limit)],
)
async def app_ai_chat(request: AIChatRequest):
    """Mobile AI access is authorized by the short-lived Quant app session."""
    run_id = None
    try:
        provider_id, model, fallback_providers = resolve_task_route(
            request.task_type, request.provider_id, request.model, request.fallback_providers
        )
        mobile_messages = [
            {
                "role": "system",
                "content": (
                    "You are Quant's evidence-bounded research assistant. "
                    "Never invent current or historical market prices, OHLCV, volume, "
                    "indicator values, timestamps, company filings, or market events. "
                    "Use only evidence explicitly supplied by the user or retrieved by Quant. "
                    "If data is missing, say so and direct the user to the verified EOD recovery "
                    "workflow. Distinguish facts from hypotheses. Do not issue buy/sell orders, "
                    "guarantee returns, or override deterministic risk controls."
                ),
            },
            *[message.model_dump() for message in request.messages if message.role != "system"],
        ]
        request_payload = {
            "messages": mobile_messages,
            "max_tokens": request.max_tokens,
            "temperature": request.temperature,
        }
        archive_error = None
        try:
            run_id = reserve_ai_research_run(
                task_type=request.task_type,
                provider_id=provider_id,
                model=model,
                credential_id=request.credential_id,
                request_payload=request_payload,
                estimated_tokens=estimate_reserved_tokens(request_payload, request.max_tokens),
            )
        except EodStoreError as error:
            if ai_token_budgets_enabled() or error.status_code == 429:
                raise HTTPException(status_code=error.status_code, detail=str(error)) from error
            archive_error = str(error)

        try:
            completion = await complete_chat(
                provider_id=provider_id,
                model=model,
                messages=mobile_messages,
                max_tokens=request.max_tokens,
                temperature=request.temperature,
                credential_id=request.credential_id,
                fallback_providers=fallback_providers,
                task_type=request.task_type,
            )
        except GatewayError:
            if run_id:
                fail_ai_research_run(run_id)
            raise

        if run_id:
            try:
                finish_ai_research_run(run_id, completion)
                completion["run_id"] = run_id
                completion["archive_status"] = "PERSISTED"
            except EodStoreError as error:
                completion["run_id"] = run_id
                completion["archive_status"] = "UNAVAILABLE"
                completion["archive_error"] = str(error)
        else:
            completion["archive_status"] = "UNAVAILABLE"
            if archive_error:
                completion["archive_error"] = archive_error
        return completion
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error

@router.post(
    "/api/v1/app/ai/strategy-brief",
    dependencies=[Depends(require_app_session), Depends(ai_request_rate_limit)],
)
async def app_ai_strategy_brief(request: AIStrategyBriefRequest):
    """Last-resort adjusted EOD data recovery followed by optional evidence-bounded AI commentary.

    TejHQ's free-key adjusted EOD endpoint is used. The AI model, when configured,
    receives a compact deterministic summary, not the raw market-data response.
    Data recovery remains available if the AI provider is unavailable.
    Intraday/scalping data is never inferred from EOD bars.
    """
    try:
        provider_id, bars = await tejhq_service.fetch_ohlcv(
            request.exchange,
            request.symbol,
            request.from_date,
            request.to_date,
            adjusted=True,
        )
        if len(bars) < 30:
            raise HTTPException(
                status_code=422,
                detail=f"Only {len(bars)} validated EOD bars were available; at least 30 are required for a useful research brief.",
            )
        bars = _apply_corporate_action_adjustment(bars)
        evidence = _build_eod_evidence(bars)
        system_prompt = (
            "You are Quant's evidence-bounded market research assistant. "
            "Use only the supplied validated corporate-action-adjusted end-of-day (EOD) price evidence. "
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
            "data_mode": "END_OF_DAY_ADJUSTED",
            "price_adjustment": "TejHQ cumulative split, bonus and dividend adjustment factor applied to OHLC.",
            "evidence": evidence,
            "interpretation_constraints": [
                "Daily bars only; never describe as intraday or live.",
                "OHLC prices are adjusted using TejHQ cumulative corporate-action factors; volume remains raw and is not used for relative-volume claims.",
                "Indicators and breakout context were calculated deterministically by Quant.",
                "A research brief is not a validated strategy, backtest, or trade signal.",
                "Do not infer missing fundamentals, corporate actions, sector breadth, or market depth.",
            ],
        }
        completion = None
        ai_error = None
        research_run = {"archive_status": "NOT_CREATED"}
        if request.automatic_task_routing or (request.provider_id and request.model):
            run_id = None
            request_payload = {
                "system_prompt": system_prompt,
                "evidence": user_evidence,
            }
            try:
                route_provider, route_model, route_fallbacks = resolve_task_route(
                    "research", request.provider_id, request.model, request.fallback_providers
                )
                try:
                    run_id = reserve_ai_research_run(
                        task_type="research",
                        provider_id=route_provider,
                        model=route_model,
                        credential_id=request.credential_id,
                        request_payload=request_payload,
                        estimated_tokens=estimate_reserved_tokens(request_payload, 1400),
                        source_refs=[{
                            "provider": provider_id,
                            "data_mode": "END_OF_DAY_ADJUSTED",
                            "exchange": request.exchange,
                            "symbol": request.symbol.upper(),
                            "from": request.from_date.isoformat(),
                            "to": request.to_date.isoformat(),
                        }],
                    )
                except EodStoreError as archive_error:
                    if ai_token_budgets_enabled() or archive_error.status_code == 429:
                        raise
                    research_run = {"archive_status": "UNAVAILABLE", "error": str(archive_error)}
                completion = await complete_chat(
                    provider_id=route_provider,
                    model=route_model,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": json.dumps(user_evidence, separators=(",", ":"), sort_keys=True)},
                    ],
                    max_tokens=1400,
                    temperature=0.1,
                    credential_id=request.credential_id,
                    fallback_providers=route_fallbacks,
                    task_type="research",
                )
                if run_id:
                    try:
                        finish_ai_research_run(run_id, completion)
                        research_run = {"run_id": run_id, "archive_status": "PERSISTED"}
                    except EodStoreError as archive_error:
                        research_run = {"run_id": run_id, "archive_status": "UNAVAILABLE", "error": str(archive_error)}
            except EodStoreError as error:
                ai_error = f"Data was recovered, but the AI token budget or research archive is unavailable: {error}"
            except GatewayError as error:
                if run_id:
                    fail_ai_research_run(run_id)
                ai_error = f"Data was recovered, but AI commentary is unavailable: {error}"
        else:
            ai_error = "Data was recovered. Select a provider/model or enable the configured research task route."

        return {
            "provider": provider_id,
            "data_mode": "END_OF_DAY_ADJUSTED",
            "price_adjustment": "TejHQ cumulative split, bonus and dividend adjustment factor applied to OHLC.",
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
            "ai_error": ai_error,
            "research_run": research_run,
            "data_rights": (
                "Private personal research only. Do not redistribute raw data or expose it to other users. "
                "Exchange terms may independently apply; this endpoint does not grant exchange display rights."
            ),
            "limitations": [
                "EOD daily bars only; not suitable for intraday or scalping strategies.",
                "OHLC prices are corporate-action adjusted; volume is raw/unadjusted and excluded from relative-volume analysis.",
                "No licensed live feed, order book, market depth, or guaranteed accuracy.",
                "AI commentary is not a trade signal or investment advice.",
                "If this application is offered to others or used commercially, obtain the required provider and exchange rights first.",
            ],
        }
    except TejHqError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except GatewayError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
