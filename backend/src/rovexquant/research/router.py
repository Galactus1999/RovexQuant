"""Versioned research HTTP routes; no engine/provider ownership lives here."""
from fastapi import APIRouter, Depends, HTTPException

from rovexquant.research.contracts import BacktestRequest, IndicatorRequest
from rovexquant.research.service import calculate_indicators, run_backtest
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
