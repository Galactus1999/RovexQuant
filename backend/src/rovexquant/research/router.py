"""Versioned research HTTP routes; no engine/provider ownership lives here."""
from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query

from rovexquant.research.contracts import BacktestRequest, IndicatorRequest
from rovexquant.research.service import calculate_indicators, run_backtest
from rovexquant.research.event_study import calculate_event_window
from rovexquant.market_data.eod_store import (
    EodStoreError, corporate_action_history_by_isin, point_in_time_symbol_history,
    point_in_time_universe, point_in_time_universe_performance,
)
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import backtest_rate_limit, indicator_rate_limit

router = APIRouter(
    prefix="/api/v1/research",
    tags=["research"],
)


@router.post(
    "/indicators",
    dependencies=[Depends(require_app_session), Depends(indicator_rate_limit)],
)
def indicators(request: IndicatorRequest):
    try:
        return calculate_indicators(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@router.post(
    "/backtest",
    dependencies=[Depends(require_app_session), Depends(backtest_rate_limit)],
)
def backtest(request: BacktestRequest):
    try:
        return run_backtest(request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@router.get(
    "/event-study/{exchange}/{symbol}",
    dependencies=[Depends(require_app_session), Depends(backtest_rate_limit)],
)
def event_study(
    exchange: str,
    symbol: str,
    event_date: date = Query(...),
    pre_bars: int = Query(10, ge=1, le=60),
    post_bars: int = Query(10, ge=1, le=60),
    benchmark_symbol: str | None = Query(default=None, min_length=1, max_length=40),
):
    """Calculate an explicitly non-causal EOD event window from persisted history."""
    try:
        bars = point_in_time_symbol_history(
            exchange, symbol,
            from_date=event_date - timedelta(days=180),
            to_date=event_date + timedelta(days=180),
            limit=2000,
        )
        asset_isin = bars[0]["isin"]
        benchmark_bars = None
        if benchmark_symbol:
            benchmark_bars = point_in_time_symbol_history(
                exchange, benchmark_symbol,
                from_date=event_date - timedelta(days=180),
                to_date=event_date + timedelta(days=180),
                limit=2000,
            )
        actions = corporate_action_history_by_isin(
            exchange, asset_isin,
            from_date=event_date - timedelta(days=60),
            to_date=event_date + timedelta(days=60),
            limit=100,
        )
        result = calculate_event_window(
            bars, event_date=event_date, pre_bars=pre_bars, post_bars=post_bars,
            benchmark_bars=benchmark_bars, benchmark_symbol=benchmark_symbol,
        )
        price_basis = (
            "CORPORATE_ACTION_ADJUSTED"
            if bars and all(bar.get("price_basis") == "CORPORATE_ACTION_ADJUSTED" for bar in bars)
            else "RAW_OR_MIXED_CLOSE"
        )
        anchor = next((bar for bar in bars if bar["date"] == result["anchor_date"]), bars[0])
        return {
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "isin": asset_isin,
            "symbol_as_of_anchor": anchor["symbol"],
            "symbol_valid_from": anchor["symbol_valid_from"],
            "symbol_valid_to": anchor["symbol_valid_to"],
            "symbol_history_provider": anchor["symbol_history_provider"],
            "universe_method": "POINT_IN_TIME_ISIN_IDENTITY",
            "data_mode": "END_OF_DAY",
            "source": "PERSISTED_MARKET_EOD_BARS",
            "price_basis": price_basis,
            "corporate_actions": actions,
            "observation_count": len(bars),
            **result,
        }
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@router.get(
    "/point-in-time/universe/{exchange}",
    dependencies=[Depends(require_app_session), Depends(backtest_rate_limit)],
)
def research_point_in_time_universe(
    exchange: str,
    as_of: date = Query(...),
    limit: int = Query(default=5000, ge=1, le=5000),
):
    """Reconstruct the NSE/BSE instrument universe that was valid on a historical date."""
    try:
        return point_in_time_universe(exchange, as_of=as_of, limit=limit)
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/point-in-time/universe-performance/{exchange}",
    dependencies=[Depends(require_app_session), Depends(backtest_rate_limit)],
)
def research_point_in_time_universe_performance(
    exchange: str,
    from_date: date = Query(...),
    to_date: date = Query(...),
    minimum_coverage: float = Query(default=0.80, ge=0.50, le=1.0),
):
    """Build a survivor-aware equal-weight universe index from adjusted prices only."""
    try:
        return point_in_time_universe_performance(
            exchange, from_date=from_date, to_date=to_date,
            minimum_coverage=minimum_coverage,
        )
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
