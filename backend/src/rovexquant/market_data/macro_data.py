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
AMFI_NAV_ENDPOINT = "https://portal.amfiindia.com/spages/NAVAll.txt"

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

    async def fetch_one(key: str) -> dict[str, Any]:
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
        return {
            "key": key, "series_id": code, "label": label,
            "frequency": frequency, "unit": unit,
            "observations": observations,
            "latest_period": observations[-1]["period"] if observations else None,
            "latest_value": observations[-1]["value"] if observations else None,
            "source": "FRED_ALFRED_API",
            "source_url": f"https://fred.stlouisfed.org/series/{code}",
            "retrieved_at": retrieved,
            "data_delay": "OFFICIAL_RELEASE_FREQUENCY",
        }

    output = await asyncio.gather(*(fetch_one(key) for key in selected))
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
            follow_redirects=True,
            headers={
                "User-Agent": "RovexQuant-Research/1.0",
                "Accept": "text/plain, text/*;q=0.9, */*;q=0.5",
            },
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
    header: dict[str, int] = {}
    for line in content.splitlines():
        fields = [field.strip() for field in line.split(";")]
        normalized = [" ".join(field.casefold().replace("_", " ").split()) for field in fields]
        if fields and normalized[0] == "scheme code":
            header = {name: index for index, name in enumerate(normalized) if name}
            continue
        if len(fields) < 6 or not fields[0].isdigit():
            continue

        # AMFI changed NAVAll.txt in August 2026 by inserting Plan and Option
        # before NAV. Prefer the published header contract; retain legacy rows.
        nav_index = next((index for name, index in header.items()
                          if name in {"net asset value", "nav"}), None)
        date_index = header.get("date")
        if nav_index is None or date_index is None or max(nav_index, date_index) >= len(fields):
            if len(fields) >= 8:
                nav_index, date_index = 6, 7
            elif len(fields) >= 6:
                nav_index, date_index = 4, 5
            else:
                continue
        try:
            nav = float(fields[nav_index])
            nav_date = fields[date_index]
            if not math.isfinite(nav) or nav < 0 or not nav_date:
                continue
        except (TypeError, ValueError):
            continue
        scheme_name = fields[3]
        if not scheme_name:
            continue
        if cleaned and cleaned not in scheme_name.casefold() and cleaned != fields[0]:
            continue
        output.append({
            "scheme_code": int(fields[0]),
            "isin_growth": fields[1] or None,
            "isin_dividend_reinvestment": fields[2] or None,
            "scheme_name": scheme_name[:240],
            "nav": nav,
            "nav_date": nav_date,
            "source": "AMFI_NAVALL_DAILY_REPORT",
            "source_url": AMFI_NAV_ENDPOINT,
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


# RBIH's DBIE endpoint is a delayed, read-only mirror, not the RBI's live API.
RBIH_DBIE_BASE_URL = "https://dbie.rbihub.in"
RBI_DBIE_SERIES = {
    "forex_reserves": ("fr_exg_resv_rn", "Foreign exchange reserves", "WEEKLY", "USD_MILLION"),
    "wholesale_price_index": ("whole_price_index_rn", "Wholesale price index", "WEEKLY", "INDEX"),
    "industrial_production": ("inx_inds_prod_rn", "Index of industrial production", "MONTHLY", "INDEX"),
}


def _extract_dbie_observations(payload: Any) -> tuple[list[dict[str, Any]], str | None, str]:
    if not isinstance(payload, dict):
        raise MacroProviderError("DBIE mirror returned an invalid JSON object")
    candidates = payload.get("series")
    if not isinstance(candidates, list):
        candidates = [payload] if isinstance(payload.get("observations"), list) else []
    output: list[dict[str, Any]] = []
    labels: list[str] = []
    as_of = payload.get("as_of")
    for series in candidates:
        if not isinstance(series, dict):
            continue
        observations = series.get("observations")
        if not isinstance(observations, list):
            continue
        labels.append(str(series.get("label") or series.get("name") or ""))
        as_of = series.get("as_of") or as_of
        for row in observations:
            if not isinstance(row, dict):
                continue
            period = row.get("period") or row.get("date") or row.get("time_period")
            raw_value = row.get("value", row.get("obs_value"))
            if period is None or raw_value is None:
                continue
            try:
                value = float(raw_value)
            except (TypeError, ValueError):
                continue
            if math.isfinite(value):
                output.append({"period": str(period)[:32], "value": value})
    if not output:
        raise MacroProviderError("DBIE mirror payload did not contain recognized numeric observations")
    output.sort(key=lambda item: item["period"])
    return output, str(as_of) if as_of else None, next((label for label in labels if label), "")


async def fetch_rbi_dbie_mirror(series: list[str] | None = None) -> dict[str, Any]:
    """Fetch a small allowlist from RBIH's delayed static DBIE mirror; no credentials."""
    selected = series or list(RBI_DBIE_SERIES)
    if not 1 <= len(selected) <= len(RBI_DBIE_SERIES):
        raise ValueError("Select between 1 and 3 RBI DBIE mirror series")
    unknown = sorted(set(selected) - RBI_DBIE_SERIES.keys())
    if unknown:
        raise ValueError(f"Unsupported RBI DBIE mirror series: {unknown[0]}")
    base = os.getenv("RBIH_DBIE_BASE_URL", RBIH_DBIE_BASE_URL).strip().rstrip("/")
    if not base.startswith("https://"):
        raise ValueError("RBIH_DBIE_BASE_URL must use HTTPS")
    retrieved = datetime.now(timezone.utc).isoformat()

    async def fetch_one(key: str) -> dict[str, Any]:
        slug, label, frequency, unit = RBI_DBIE_SERIES[key]
        payload = await _get_json(f"{base}/data/sdmx-{slug}.json", {})
        observations, as_of, mirror_label = _extract_dbie_observations(payload)
        if not observations:
            raise MacroProviderError(f"DBIE mirror series {slug} contained no usable observations")
        return {
            "key": key, "series_id": slug.upper(), "label": mirror_label or label,
            "frequency": frequency, "unit": unit, "observations": observations,
            "latest_period": observations[-1]["period"], "latest_value": observations[-1]["value"],
            "as_of": as_of or observations[-1]["period"],
            "source": "RBIH_DBIE_STATIC_MIRROR",
            "source_url": f"{base}/data/sdmx-{slug}.json",
            "retrieved_at": retrieved, "data_delay": "MIRROR_LAST_SCRAPE_NOT_LIVE",
            "primary_source": "https://data.rbi.org.in/DBIE/",
        }

    output = await asyncio.gather(*(fetch_one(key) for key in selected))
    return {
        "provider": "RBIH_DBIE_MIRROR", "configured": True, "retrieved_at": retrieved,
        "data_vintage": "RBIH_MIRROR_LAST_SCRAPE", "series": output,
        "limitations": [
            "This is RBIH's read-only static mirror of RBI DBIE, not a live RBI API.",
            "Observation freshness is bounded by the mirror's last scrape; use each series as_of field.",
            "Verify material observations against the RBI portal before relying on them.",
        ],
    }
