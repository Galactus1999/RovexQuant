"""Versioned research HTTP routes; no engine/provider ownership lives here."""
from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query

from rovexquant.research.contracts import BacktestRequest, IndicatorRequest
from rovexquant.research.service import calculate_indicators, run_backtest
from rovexquant.research.event_study import calculate_event_window
from rovexquant.market_data.eod_store import EodStoreError, symbol_history
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
        bars = symbol_history(
            exchange, symbol,
            from_date=event_date - timedelta(days=180),
            to_date=event_date + timedelta(days=180),
            limit=2000,
        )
        benchmark_bars = None
        if benchmark_symbol:
            benchmark_bars = symbol_history(
                exchange, benchmark_symbol,
                from_date=event_date - timedelta(days=180),
                to_date=event_date + timedelta(days=180),
                limit=2000,
            )
        result = calculate_event_window(
            bars, event_date=event_date, pre_bars=pre_bars, post_bars=post_bars,
            benchmark_bars=benchmark_bars, benchmark_symbol=benchmark_symbol,
        )
        return {
            "exchange": exchange.upper(),
            "symbol": symbol.upper(),
            "data_mode": "END_OF_DAY",
            "source": "PERSISTED_MARKET_EOD_BARS",
            "observation_count": len(bars),
            **result,
        }
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
