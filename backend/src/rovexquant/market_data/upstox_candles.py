"""Authorized Upstox V3 OHLC candle adapter for historical and current-session chart resolutions."""
from __future__ import annotations

from datetime import date, datetime, timedelta
import math
import os
import re
from typing import Any
from urllib.parse import quote
from zoneinfo import ZoneInfo

import httpx

from rovexquant.market_data.instruments import (
    InstrumentMasterError,
    get_nse_equity_instruments,
    get_nse_index_instruments,
)
from rovexquant.market_data.upstox_credentials import get_upstox_analytics_token


class UpstoxCandleError(Exception):
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


_INTERVALS = {
    "1d": ("days", 1, 3653),
    "1m": ("minutes", 1, 30),
    "5m": ("minutes", 5, 30),
    "10m": ("minutes", 10, 30),
    "15m": ("minutes", 15, 30),
    "30m": ("minutes", 30, 90),
    "1h": ("hours", 1, 90),
    "4h": ("hours", 4, 90),
}
_KEY_PATTERN = re.compile(
    r"^(?:NSE_EQ\|IN[A-Z0-9]{10}|NSE_INDEX\|[A-Za-z0-9 _&()./-]{1,80})$"
)
_MARKET_ZONE = ZoneInfo("Asia/Kolkata")
_MAX_CANDLES = 20_000


def interval_contract(interval: str) -> tuple[str, int, int]:
    try:
        return _INTERVALS[interval]
    except KeyError as error:
        raise UpstoxCandleError("Unsupported candle interval.", 422) from error


async def resolve_nse_instrument_key(symbol: str) -> str:
    normalized = " ".join(symbol.strip().upper().split())
    if not normalized or len(normalized) > 80:
        raise UpstoxCandleError("A valid NSE company symbol or index name is required.", 422)

    def canonical_label(value: str) -> str:
        normalized_label = str(value or "").upper().replace("&", " AND ")
        normalized_label = re.sub(r"[^A-Z0-9]+", " ", normalized_label)
        normalized_label = " ".join(normalized_label.split())
        if normalized_label.endswith(" INDEX"):
            normalized_label = normalized_label[:-6].strip()
        return normalized_label

    def matching(records: list[dict[str, Any]], query: str) -> list[dict[str, Any]]:
        normalized_query = canonical_label(query)
        aliases = {
            "BANKNIFTY": ("NIFTY BANK",),
            "NIFTY FINANCIAL SERVICES": ("NIFTY FIN SERVICE",),
            "NIFTY TELECOMMUNICATIONS": ("NIFTY TELECOM",),
            "NIFTY TELECOM": ("NIFTY TELECOMMUNICATIONS",),
        }
        candidates = [normalized_query, *aliases.get(normalized_query, ())]
        for candidate in candidates:
            matches = [
                record for record in records
                if candidate in {
                    canonical_label(record.get("name", "")),
                    canonical_label(record.get("trading_symbol", "")),
                }
            ]
            if matches:
                return matches
        return []
    index_master_error: InstrumentMasterError | None = None
    if normalized.startswith("NIFTY") or normalized.startswith("BANKNIFTY"):
        index_query = "NIFTY BANK" if normalized == "BANKNIFTY" else normalized
        try:
            index_matches = matching(await get_nse_index_instruments(), index_query)
            if len(index_matches) > 1:
                raise UpstoxCandleError("Index name resolves to multiple current NSE instruments.", 409)
            if index_matches:
                key = index_matches[0].get("instrument_key")
                if isinstance(key, str) and _KEY_PATTERN.fullmatch(key):
                    return key
                raise UpstoxCandleError("NSE index master returned an invalid instrument key.", 502)
        except InstrumentMasterError as error:
            index_master_error = error

    try:
        equity_matches = matching(await get_nse_equity_instruments(), normalized)
    except InstrumentMasterError as error:
        if index_master_error is not None:
            raise UpstoxCandleError(
                "The verified NSE instrument masters are temporarily unavailable.", 503
            ) from error
        raise UpstoxCandleError(
            "The verified NSE equity master is temporarily unavailable.", 503
        ) from error

    if equity_matches:
        key = equity_matches[0].get("instrument_key")
        if isinstance(key, str) and _KEY_PATTERN.fullmatch(key):
            return key
        raise UpstoxCandleError("NSE equity master returned an invalid instrument key.", 502)
    if index_master_error is not None:
        raise UpstoxCandleError(
            "The verified NSE index master is temporarily unavailable.", 503
        ) from index_master_error
    raise UpstoxCandleError("Instrument is not present in the current verified NSE master.", 404)


def _parse_candles(payload: Any) -> list[dict[str, Any]]:
    try:
        rows = payload["data"]["candles"]
    except (TypeError, KeyError):
        raise UpstoxCandleError("Upstox returned an invalid candle response.") from None
    if not isinstance(rows, list):
        raise UpstoxCandleError("Upstox candle response is not a list.")
    parsed: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, (list, tuple)) or len(row) < 6:
            raise UpstoxCandleError("Upstox returned a malformed OHLC candle.")
        try:
            timestamp = datetime.fromisoformat(str(row[0]).replace("Z", "+00:00"))
            values = [float(row[index]) for index in range(1, 5)]
            volume_number = float(row[5] or 0)
        except (TypeError, ValueError, OverflowError):
            raise UpstoxCandleError("Upstox returned a non-numeric OHLC candle.") from None
        if timestamp.tzinfo is None or timestamp.utcoffset() is None:
            raise UpstoxCandleError("Upstox candle timestamps must include a timezone.")
        opening, high, low, close = values
        if (
            not all(math.isfinite(value) and value > 0 for value in values)
            or not math.isfinite(volume_number) or volume_number < 0 or not volume_number.is_integer()
            or high < max(opening, close) or low > min(opening, close) or high < low
        ):
            raise UpstoxCandleError("Upstox returned an invalid OHLCV range.")
        parsed.append({
            "timestamp": timestamp,
            "open": opening,
            "high": high,
            "low": low,
            "close": close,
            "volume": int(volume_number),
        })
    return parsed


async def fetch_intraday_candles(
    exchange: str,
    symbol: str,
    interval: str,
    from_date: date,
    to_date: date,
    *,
    transport: httpx.AsyncBaseTransport | None = None,
    today: date | None = None,
) -> dict[str, Any]:
    if exchange.strip().upper() != "NSE":
        raise UpstoxCandleError("Upstox candles are currently enabled for NSE only.", 422)
    unit, resolution, maximum_days = interval_contract(interval)
    if from_date > to_date:
        raise UpstoxCandleError("The chart start date must not be after its end date.", 422)
    market_today = today or datetime.now(_MARKET_ZONE).date()
    if to_date > market_today:
        raise UpstoxCandleError("Chart end date cannot be in the future.", 422)
    if (to_date - from_date).days > maximum_days:
        raise UpstoxCandleError(
            f"{interval} history is limited to approximately {maximum_days} calendar days per request.",
            422,
        )
    token = get_upstox_analytics_token() or os.getenv("UPSTOX_ANALYTICS_TOKEN", "").strip()
    if not token:
        raise UpstoxCandleError("Upstox Analytics Token is not configured in the server-side credential vault.", 503)
    instrument_key = await resolve_nse_instrument_key(symbol)
    encoded_key = quote(instrument_key, safe="")
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    timeout = httpx.Timeout(20.0, connect=8.0)
    candles_by_timestamp: dict[datetime, dict[str, Any]] = {}

    async def request_candles(client: httpx.AsyncClient, url: str) -> list[dict[str, Any]]:
        try:
            response = await client.get(url, headers=headers)
        except httpx.TimeoutException as error:
            raise UpstoxCandleError("Upstox candle request timed out.", 503) from error
        except httpx.RequestError as error:
            raise UpstoxCandleError("Upstox candle service is unreachable.", 503) from error
        if response.status_code in (401, 403):
            raise UpstoxCandleError("Upstox rejected the server-side Analytics Token.", 502)
        if response.status_code == 429 or response.status_code >= 500:
            raise UpstoxCandleError("Upstox candle service is temporarily unavailable.", 503)
        if response.status_code != 200:
            raise UpstoxCandleError(f"Upstox candle request failed (HTTP {response.status_code}).", 502)
        try:
            return _parse_candles(response.json())
        except ValueError as error:
            raise UpstoxCandleError("Upstox returned invalid JSON for candle history.") from error

    async with httpx.AsyncClient(timeout=timeout, transport=transport) as client:
        # Historical V3 serves completed dates. The separate intraday V3 endpoint
        # supplies today's evolving candle (including the 1d daily candle); merge by exchange timestamp.
        historical_to = min(to_date, market_today - timedelta(days=1))
        if from_date <= historical_to:
            historical_url = (
                f"https://api.upstox.com/v3/historical-candle/{encoded_key}/"
                f"{unit}/{resolution}/{historical_to.isoformat()}/{from_date.isoformat()}"
            )
            for candle in await request_candles(client, historical_url):
                candles_by_timestamp[candle["timestamp"]] = candle
        if to_date >= market_today and from_date <= market_today:
            intraday_url = (
                f"https://api.upstox.com/v3/historical-candle/intraday/"
                f"{encoded_key}/{unit}/{resolution}"
            )
            for candle in await request_candles(client, intraday_url):
                if candle["timestamp"].astimezone(_MARKET_ZONE).date() == market_today:
                    candles_by_timestamp[candle["timestamp"]] = candle

    candles = sorted(candles_by_timestamp.values(), key=lambda item: item["timestamp"])
    if len(candles) > _MAX_CANDLES:
        candles = candles[-_MAX_CANDLES:]
    return {
        "provider": "UPSTOX_V3",
        "data_mode": "DAILY_HISTORY_AND_CURRENT_SESSION" if interval == "1d" else "INTRADAY_HISTORICAL",
        "exchange": "NSE",
        "symbol": symbol.strip().upper(),
        "instrument_key": instrument_key,
        "interval": interval,
        "from": from_date.isoformat(),
        "to": to_date.isoformat(),
        "volume_available": instrument_key.startswith("NSE_EQ|"),
        "retrieved_at": datetime.now(_MARKET_ZONE),
        "bars": candles,
        "count": len(candles),
        "history_limit_days": maximum_days,
    }
