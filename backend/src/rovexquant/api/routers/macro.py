"""Authenticated macroeconomic research endpoints."""
from fastapi import APIRouter, Depends, HTTPException, Query

from rovexquant.market_data.macro_data import (
    MacroProviderError, fetch_amfi_nav, fetch_fred, fetch_world_bank,
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



@router.get(
    "/api/v1/app/research/macro/amfi-nav",
    dependencies=[Depends(require_app_session), Depends(macro_rate_limit)],
)
async def amfi_nav(
    search: str = Query(default="", max_length=100),
    limit: int = Query(default=50, ge=1, le=100),
):
    try:
        return await fetch_amfi_nav(search, limit)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except MacroProviderError as error:
        raise HTTPException(status_code=502, detail=str(error)) from error


from rovexquant.market_data.eod_store import EodStoreError
from rovexquant.market_data.macro_ingestion import refresh_macro_sources
from rovexquant.market_data.macro_store import latest_macro_observations, latest_macro_runs


@router.post(
    "/api/v1/app/research/macro/refresh",
    dependencies=[Depends(require_app_session), Depends(macro_rate_limit)],
)
async def refresh_macro():
    """Fetch allowlisted free macro providers and persist versioned observations."""
    result = await refresh_macro_sources()
    if not any(item.get("status") == "SUCCEEDED" for item in result["providers"]):
        raise HTTPException(status_code=503, detail="No macro provider could be refreshed and persisted.")
    return result


@router.get(
    "/api/v1/app/research/macro/history",
    dependencies=[Depends(require_app_session), Depends(macro_rate_limit)],
)
def macro_history(
    provider: str | None = Query(default=None, max_length=32),
    series_key: str | None = Query(default=None, max_length=160),
    limit: int = Query(default=500, ge=1, le=2000),
):
    try:
        return latest_macro_observations(provider=provider, series_key=series_key, limit=limit)
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/research/macro/status",
    dependencies=[Depends(require_app_session), Depends(macro_rate_limit)],
)
def macro_status(limit: int = Query(default=20, ge=1, le=100)):
    try:
        return {"providers": latest_macro_runs(limit=limit)}
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
