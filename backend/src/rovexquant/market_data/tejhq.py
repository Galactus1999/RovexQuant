"""TejHQ EOD research-data adapter.

This adapter is for one person's private research only. It deliberately exposes
EOD data as EOD and never upgrades it to live/delayed quote semantics.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
import math
import os
import re
from typing import Any

import httpx


_SYMBOL = re.compile(r"^[A-Z0-9][A-Z0-9-&]{0,29}$")
_EXCHANGES = {"nse", "bse"}
_MAX_RANGE_DAYS = 3660
_MAX_ROWS = 5000


class TejHqError(Exception):
    def __init__(self, message: str, status_code: int = 502, *, retryable: bool = False):
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable


@dataclass(frozen=True, slots=True)
class TejHqDailyBar:
    date: date
    open: float
    high: float
    low: float
    close: float
    volume: int
    last: float | None = None
    previous_close: float | None = None
    turnover: float | None = None
    trades: int | None = None
    adjusted_close: float | None = None
    adjustment_factor: float | None = None


def _finite_number(value: Any, field: str, *, positive: bool = False) -> float:
    if isinstance(value, bool):
        raise TejHqError(f"TejHQ returned invalid {field}", 502)
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise TejHqError(f"TejHQ returned invalid {field}", 502) from error
    if not math.isfinite(number) or (positive and number <= 0):
        raise TejHqError(f"TejHQ returned invalid {field}", 502)
    return number


def _optional_number(value: Any, field: str) -> float | None:
    if value is None:
        return None
    return _finite_number(value, field)


def _validate_exchange_symbol(exchange: str, symbol: str) -> tuple[str, str]:
    exchange_value = exchange.strip().lower()
    symbol_value = symbol.strip().upper()
    if exchange_value not in _EXCHANGES:
        raise TejHqError("Exchange must be NSE or BSE", 422)
    if not _SYMBOL.fullmatch(symbol_value):
        raise TejHqError("Invalid exchange trading symbol", 422)
    return exchange_value, symbol_value


def _rows(payload: Any) -> list[dict[str, Any]]:
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), list):
        raise TejHqError("TejHQ response has an invalid data envelope", 502)
    rows = payload["data"]
    if len(rows) > _MAX_ROWS or any(not isinstance(row, dict) for row in rows):
        raise TejHqError("TejHQ response exceeds row limits or has invalid rows", 502)
    return rows


def _normalize_bars(payload: Any, *, require_adjusted: bool = False) -> list[TejHqDailyBar]:
    normalized: list[TejHqDailyBar] = []
    seen: set[date] = set()
    for row in _rows(payload):
        try:
            day = date.fromisoformat(str(row["date"]))
        except (KeyError, TypeError, ValueError) as error:
            raise TejHqError("TejHQ returned an invalid trading date", 502) from error
        if day in seen:
            raise TejHqError("TejHQ returned duplicate trading dates", 502)
        seen.add(day)
        opening = _finite_number(row.get("open"), "open", positive=True)
        high = _finite_number(row.get("high"), "high", positive=True)
        low = _finite_number(row.get("low"), "low", positive=True)
        close = _finite_number(row.get("close"), "close", positive=True)
        volume_value = _finite_number(row.get("volume"), "volume")
        if volume_value < 0 or not volume_value.is_integer():
            raise TejHqError("TejHQ returned invalid volume", 502)
        if low > min(opening, close) or high < max(opening, close) or low > high:
            raise TejHqError("TejHQ returned inconsistent OHLC values", 502)
        trades_value = _finite_number(row["trades"], "trades") if row.get("trades") is not None else None
        if trades_value is not None and (trades_value < 0 or not trades_value.is_integer()):
            raise TejHqError("TejHQ returned invalid trade count", 502)
        adjusted_close = _optional_number(row.get("adj_close"), "adj_close")
        adjustment_factor = _optional_number(row.get("adj_factor_cumulative"), "adj_factor_cumulative")
        if require_adjusted and (adjusted_close is None or adjustment_factor is None or adjustment_factor <= 0):
            raise TejHqError("Adjusted TejHQ row is missing a valid adjusted close/factor", 502)
        normalized.append(TejHqDailyBar(
            date=day, open=opening, high=high, low=low, close=close,
            volume=int(volume_value),
            last=_optional_number(row.get("last"), "last"),
            previous_close=_optional_number(row.get("prev_close"), "prev_close"),
            turnover=_optional_number(row.get("turnover"), "turnover"),
            trades=int(trades_value) if trades_value is not None else None,
            adjusted_close=adjusted_close,
            adjustment_factor=adjustment_factor,
        ))
    normalized.sort(key=lambda item: item.date)
    return normalized


class TejHqEodProvider:
    """Async client for documented TejHQ personal-research endpoints."""

    provider_id = "tejhq_eod"

    def __init__(
        self,
        api_key: str = "",
        *,
        base_url: str = "https://api.tejhq.dev",
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self._api_key = api_key.strip()
        self._base_url = base_url.rstrip("/")
        self._transport = transport

    @classmethod
    def from_environment(cls) -> "TejHqEodProvider":
        return cls(
            os.getenv("TEJHQ_API_KEY", ""),
            base_url=os.getenv("TEJHQ_BASE_URL", "https://api.tejhq.dev"),
        )

    @property
    def configured(self) -> bool:
        # Keyless EOD OHLCV/snapshot/actions are available without a credential.
        return True

    @property
    def free_key_configured(self) -> bool:
        return bool(self._api_key)

    async def _get(self, path: str, params: dict[str, str] | None = None, *, requires_key: bool = False) -> Any:
        if requires_key and not self._api_key:
            raise TejHqError("This TejHQ endpoint requires the optional free API key", 503)
        headers = {"Accept": "application/json"}
        if requires_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(15.0, connect=5.0),
                follow_redirects=False,
                transport=self._transport,
            ) as client:
                response = await client.get(
                    f"{self._base_url}{path}", params=params, headers=headers
                )
        except httpx.TimeoutException as error:
            raise TejHqError("TejHQ request timed out", 503, retryable=True) from error
        except httpx.RequestError as error:
            raise TejHqError("TejHQ service is unreachable", 503, retryable=True) from error
        if response.status_code == 429 or response.status_code >= 500:
            raise TejHqError(f"TejHQ temporarily unavailable (HTTP {response.status_code})", 503, retryable=True)
        if response.status_code in (401, 403):
            raise TejHqError("TejHQ rejected the configured free API key", 502)
        if response.status_code >= 400:
            raise TejHqError(f"TejHQ rejected the request (HTTP {response.status_code})", 422)
        try:
            return response.json()
        except ValueError as error:
            raise TejHqError("TejHQ returned invalid JSON", 502) from error

    async def get_ohlcv(
        self, exchange: str, symbol: str, from_date: date, to_date: date, *, adjusted: bool = False
    ) -> list[TejHqDailyBar]:
        exchange_value, symbol_value = _validate_exchange_symbol(exchange, symbol)
        if from_date > to_date:
            raise TejHqError("from_date must be on or before to_date", 422)
        if (to_date - from_date).days > _MAX_RANGE_DAYS:
            raise TejHqError("Requested history range exceeds ten years", 422)
        endpoint = "adjusted" if adjusted else "ohlcv"
        payload = await self._get(
            f"/v1/{endpoint}/{exchange_value}/{symbol_value}",
            {"from": from_date.isoformat(), "to": to_date.isoformat()},
            requires_key=adjusted,
        )
        bars = _normalize_bars(payload, require_adjusted=adjusted)
        if any(item.date < from_date or item.date > to_date for item in bars):
            raise TejHqError("TejHQ returned a row outside the requested date range", 502)
        return bars

    async def get_delivery(self, exchange: str, symbol: str, from_date: date, to_date: date) -> list[dict[str, Any]]:
        exchange_value, symbol_value = _validate_exchange_symbol(exchange, symbol)
        if from_date > to_date or (to_date - from_date).days > _MAX_RANGE_DAYS:
            raise TejHqError("Invalid delivery date range", 422)
        payload = await self._get(
            f"/v1/delivery/{exchange_value}/{symbol_value}",
            {"from": from_date.isoformat(), "to": to_date.isoformat()},
            requires_key=True,
        )
        return _rows(payload)

    async def get_snapshot(self, exchange: str, trading_date: date) -> list[dict[str, Any]]:
        exchange_value = exchange.strip().lower()
        if exchange_value not in _EXCHANGES:
            raise TejHqError("Exchange must be NSE or BSE", 422)
        payload = await self._get(
            f"/v1/snapshot/{exchange_value}", {"date": trading_date.isoformat()}
        )
        return _rows(payload)

    async def get_actions(self, symbol: str) -> list[dict[str, Any]]:
        _, symbol_value = _validate_exchange_symbol("nse", symbol)
        payload = await self._get(f"/v1/actions/{symbol_value}")
        return _rows(payload)

    async def get_status(self) -> dict[str, Any]:
        payload = await self._get("/v1/status")
        if not isinstance(payload, dict):
            raise TejHqError("TejHQ status response has an invalid shape", 502)
        return payload

    def safe_status(self) -> dict[str, Any]:
        return {
            "provider_id": self.provider_id,
            "configured": True,
            "mode": "END_OF_DAY",
            "free_key_configured": self.free_key_configured,
            "keyless_endpoints": ["ohlcv", "snapshot", "actions"],
            "free_key_endpoints": ["adjusted", "delivery", "symbols"],
            "usage_note": "Personal research only; do not redistribute source data.",
        }
