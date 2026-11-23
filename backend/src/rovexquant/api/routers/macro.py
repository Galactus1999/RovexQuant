"""Authenticated macroeconomic research endpoints."""
from fastapi import APIRouter, Depends, HTTPException, Query

from rovexquant.market_data.macro_data import (
    MacroProviderError, fetch_fred, fetch_world_bank,
)
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import rate_limit

router = APIRouter()
macro_rate_limit = rate_limit("macro-research", limit=12, window_seconds=60)


@router.get(
    "/api/v1/app/research/macro/world-bank",
    dependencies=[Depends(require_app_session), Depends(macro_rate_limit)],
)
async def world_bank_macro(
    indicators: list[str] = Query(default=["gdp_growth", "inflation", "exports_usd", "current_account_usd"]),
):
    try:
        return await fetch_world_bank(indicators)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except MacroProviderError as error:
        raise HTTPException(status_code=502, detail=str(error)) from error


@router.get(
    "/api/v1/app/research/macro/fred",
    dependencies=[Depends(require_app_session), Depends(macro_rate_limit)],
)
async def fred_macro(
    series: list[str] = Query(default=["us_10y_treasury", "us_fed_funds", "us_cpi", "broad_us_dollar"]),
):
    try:
        return await fetch_fred(series)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except MacroProviderError as error:
        raise HTTPException(status_code=502, detail=str(error)) from error
