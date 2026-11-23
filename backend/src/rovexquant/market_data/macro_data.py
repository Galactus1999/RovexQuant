"""Free macroeconomic data adapters with explicit provenance and no paid dependency.

World Bank v2 is keyless. FRED is optional and only enabled when a server-side
FRED_API_KEY is configured. RBI remains an official-source scheduled/manual
import until a stable supported machine interface is verified.
"""
from __future__ import annotations

import asyncio
import math
import os
from datetime import datetime, timezone
from typing import Any

import httpx

WORLD_BANK_ENDPOINT = "https://api.worldbank.org/v2/country/IND/indicator"
FRED_ENDPOINT = "https://api.stlouisfed.org/fred/series/observations"
AMFI_NAV_ENDPOINT = "https://www.amfiindia.com/spages/NAVAll.txt"

WORLD_BANK_INDICATORS = {
    "gdp_growth": ("NY.GDP.MKTP.KD.ZG", "GDP growth (annual %)", "ANNUAL", "PERCENT"),
    "inflation": ("FP.CPI.TOTL.ZG", "Consumer price inflation (annual %)", "ANNUAL", "PERCENT"),
    "exports_usd": ("NE.EXP.GNFS.CD", "Exports of goods and services (current US$)", "ANNUAL", "USD"),
    "current_account_usd": ("BN.CAB.XOKA.CD", "Current account balance (BoP, current US$)", "ANNUAL", "USD"),
    "energy_use": ("EG.USE.PCAP.KG.OE", "Energy use (kg of oil equivalent per capita)", "ANNUAL", "KG_OE_PER_CAPITA"),
}
FRED_SERIES = {
    "us_10y_treasury": ("DGS10", "10-Year Treasury Constant Maturity Rate", "DAILY", "PERCENT"),
    "us_fed_funds": ("FEDFUNDS", "Federal Funds Effective Rate", "MONTHLY", "PERCENT"),
    "us_cpi": ("CPIAUCSL", "Consumer Price Index for All Urban Consumers", "MONTHLY", "INDEX"),
    "broad_us_dollar": ("DTWEXBGS", "Nominal Broad U.S. Dollar Index", "DAILY", "INDEX"),
}


class MacroProviderError(RuntimeError):
    """Upstream macro provider failure without leaking request credentials."""


async def _get_json(url: str, params: dict[str, str | int]) -> Any:
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(15.0, connect=4.0),
            headers={"User-Agent": "RovexQuant-Research/1.0"},
        ) as client:
            response = await client.get(url, params=params)
            response.raise_for_status()
            if "json" not in response.headers.get("content-type", "").lower():
                raise MacroProviderError("Macro provider returned a non-JSON response")
            return response.json()
    except MacroProviderError:
        raise
    except (httpx.HTTPError, ValueError) as error:
        raise MacroProviderError("Macroeconomic data provider is temporarily unavailable") from error


async def fetch_world_bank(indicators: list[str] | None = None) -> dict[str, Any]:
    selected = indicators or list(WORLD_BANK_INDICATORS)
    if not 1 <= len(selected) <= len(WORLD_BANK_INDICATORS):
        raise ValueError("Select between 1 and 5 World Bank indicators")
    unknown = sorted(set(selected) - WORLD_BANK_INDICATORS.keys())
    if unknown:
        raise ValueError(f"Unsupported World Bank indicator: {unknown[0]}")
    retrieved = datetime.now(timezone.utc).isoformat()

    async def fetch_one(key: str) -> dict[str, Any]:
        code, label, frequency, unit = WORLD_BANK_INDICATORS[key]
        payload = await _get_json(
            f"{WORLD_BANK_ENDPOINT}/{code}",
            {"format": "json", "per_page": 100, "date": "1960:2100"},
        )
        if not isinstance(payload, list) or len(payload) < 2 or not isinstance(payload[1], list):
            raise MacroProviderError("World Bank returned an invalid indicator payload")
        observations = []
        for row in payload[1]:
            if not isinstance(row, dict) or row.get("value") is None:
                continue
            try:
                value = float(row["value"])
                year = int(row["date"])
            except (TypeError, ValueError):
                continue
            if math.isfinite(value):
                observations.append({"period": str(year), "value": value})
        observations.sort(key=lambda item: item["period"])
        return {
            "key": key, "indicator_code": code, "label": label,
            "country": "IND", "frequency": frequency, "unit": unit,
            "observations": observations,
            "latest_period": observations[-1]["period"] if observations else None,
            "latest_value": observations[-1]["value"] if observations else None,
            "source": "WORLD_BANK_INDICATORS_V2",
            "source_url": f"https://api.worldbank.org/v2/country/IND/indicator/{code}",
            "retrieved_at": retrieved,
            "data_delay": "LOW_FREQUENCY_OFFICIAL_RELEASE",
        }

    # Bounded to the five-indicator allowlist; concurrent fetch avoids serial API latency.
    output = await asyncio.gather(*(fetch_one(key) for key in selected))
    return {"provider": "WORLD_BANK", "retrieved_at": retrieved, "series": output}


async def fetch_fred(series: list[str] | None = None) -> dict[str, Any]:
    api_key = os.getenv("FRED_API_KEY", "").strip()
    if not api_key:
        return {
            "provider": "FRED", "configured": False, "series": [],
            "reason": "FRED_API_KEY is not configured; add it as a backend secret.",
        }
    selected = series or list(FRED_SERIES)
    if not 1 <= len(selected) <= len(FRED_SERIES):
        raise ValueError("Select between 1 and 4 FRED series")
    unknown = sorted(set(selected) - FRED_SERIES.keys())
    if unknown:
        raise ValueError(f"Unsupported FRED series: {unknown[0]}")
    retrieved = datetime.now(timezone.utc).isoformat()
    output = []
    for key in selected:
        code, label, frequency, unit = FRED_SERIES[key]
        payload = await _get_json(FRED_ENDPOINT, {
            "series_id": code, "api_key": api_key, "file_type": "json",
            "sort_order": "desc", "limit": 24,
        })
        observations = []
        for row in payload.get("observations", []) if isinstance(payload, dict) else []:
            if not isinstance(row, dict) or row.get("value") in (None, "."):
                continue
            try:
                observations.append({"period": str(row["date"]), "value": float(row["value"])})
            except (KeyError, TypeError, ValueError):
                continue
        observations.sort(key=lambda item: item["period"])
        output.append({
            "key": key, "series_id": code, "label": label,
            "frequency": frequency, "unit": unit,
            "observations": observations,
            "latest_period": observations[-1]["period"] if observations else None,
            "latest_value": observations[-1]["value"] if observations else None,
            "source": "FRED_ALFRED_API",
            "source_url": f"https://fred.stlouisfed.org/series/{code}",
            "retrieved_at": retrieved,
            "data_delay": "OFFICIAL_RELEASE_FREQUENCY",
        })
    return {"provider": "FRED", "configured": True, "retrieved_at": retrieved, "series": output}



async def fetch_amfi_nav(search: str = "", limit: int = 50) -> dict[str, Any]:
    """Fetch and parse AMFI's published daily NAV text report; never treat NAV as equity price."""
    cleaned = " ".join(search.split()).casefold()
    if len(cleaned) > 100:
        raise ValueError("AMFI search must be at most 100 characters")
    if not 1 <= limit <= 100:
        raise ValueError("AMFI result limit must be between 1 and 100")
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(20.0, connect=5.0),
            headers={"User-Agent": "RovexQuant-Research/1.0"},
        ) as client:
            response = await client.get(AMFI_NAV_ENDPOINT)
            response.raise_for_status()
            raw = response.content
    except httpx.HTTPError as error:
        raise MacroProviderError("AMFI NAV report is temporarily unavailable") from error
    if len(raw) > 12 * 1024 * 1024:
        raise MacroProviderError("AMFI NAV report exceeded the 12 MB safety limit")
    try:
        content = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise MacroProviderError("AMFI NAV report encoding was not recognized") from error
    retrieved = datetime.now(timezone.utc).isoformat()
    output = []
    for line in content.splitlines():
        fields = [field.strip() for field in line.split(";")]
        if len(fields) < 6 or not fields[0].isdigit():
            continue
        try:
            nav = float(fields[4])
            if not math.isfinite(nav) or nav < 0 or not fields[5]:
                continue
        except ValueError:
            continue
        scheme_name = fields[3]
        if cleaned and cleaned not in scheme_name.casefold() and cleaned != fields[0]:
            continue
        output.append({
            "scheme_code": int(fields[0]),
            "isin_growth": fields[1] or None,
            "isin_dividend_reinvestment": fields[2] or None,
            "scheme_name": scheme_name[:240],
            "nav": nav,
            "nav_date": fields[5],
            "source": "AMFI_NAVALL_DAILY_REPORT",
            "source_url": "https://www.amfiindia.com/net-asset-value/nav-download",
            "retrieved_at": retrieved,
            "data_mode": "DAILY_MUTUAL_FUND_NAV",
        })
        if len(output) >= limit:
            break
    return {
        "provider": "AMFI",
        "configured": True,
        "retrieved_at": retrieved,
        "result_count": len(output),
        "search": search,
        "data_mode": "DAILY_MUTUAL_FUND_NAV",
        "not_equity_quotes": True,
        "source_url": "https://www.amfiindia.com/net-asset-value/nav-download",
        "observations": output,
    }
