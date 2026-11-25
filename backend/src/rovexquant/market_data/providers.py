"""Provider adapters for normalized market quote snapshots.

This integration starts with REST snapshots. A snapshot is never represented as a
continuous WebSocket stream; the V3 streaming adapter is a separate follow-on.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Protocol, Sequence

import httpx


@dataclass(frozen=True, slots=True)
class QuoteInstrument:
    symbol: str
    upstox_instrument_key: str | None = None
    dhan_exchange_segment: str | None = None
    dhan_security_id: str | None = None


@dataclass(frozen=True, slots=True)
class MarketQuote:
    symbol: str
    provider: str
    last_price: float | None
    previous_close: float | None
    net_change: float | None
    change_percent: float | None
    volume: int | None
    exchange_timestamp: datetime | None
    received_at: datetime
    quality: str
    year_high: float | None = None
    year_low: float | None = None


class ProviderError(Exception):
    def __init__(self, provider: str, message: str, *, retryable: bool, status_code: int = 502):
        super().__init__(message)
        self.provider = provider
        self.retryable = retryable
        self.status_code = status_code


class QuoteProvider(Protocol):
    provider_id: str

    @property
    def configured(self) -> bool: ...

    async def get_quotes(self, instruments: Sequence[QuoteInstrument]) -> list[MarketQuote]: ...


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if result == result and abs(result) != float("inf") else None


def _integer(value: Any) -> int | None:
    result = _number(value)
    return int(result) if result is not None and result >= 0 else None


def _timestamp(value: Any) -> datetime | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        seconds = float(value) / 1000 if value > 10_000_000_000 else float(value)
        try:
            return datetime.fromtimestamp(seconds, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
    if isinstance(value, str) and value.strip():
        raw = value.strip()
        try:
            return _timestamp(float(raw))
        except ValueError:
            pass
        try:
            parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
            return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
        except ValueError:
            return None
    return None


def _normalize_quote(symbol: str, provider: str, raw: dict[str, Any], received_at: datetime) -> MarketQuote:
    ohlc = raw.get("ohlc") if isinstance(raw.get("ohlc"), dict) else {}
    last = _number(raw.get("last_price", raw.get("ltp")))
    previous = _number(raw.get("prev_close_price", raw.get("previous_close", ohlc.get("close"))))
    change = _number(raw.get("net_change"))
    if change is None and last is not None and previous is not None:
        change = last - previous
    change_pct = (change / previous) * 100 if change is not None and previous is not None and previous > 0 else None
    exchange_ts = _timestamp(raw.get("timestamp", raw.get("last_trade_time", raw.get("ts"))))
    quality = "TIMESTAMP_UNAVAILABLE" if exchange_ts is None else "VALID"
    if exchange_ts is not None:
        age = (received_at - exchange_ts).total_seconds()
        if age < -5:
            quality = "FUTURE_TIMESTAMP"
        elif age > 30:
            quality = "STALE"
    if last is None or last <= 0:
        quality = "INVALID_PRICE"
    return MarketQuote(
        symbol=symbol, provider=provider, last_price=last, previous_close=previous,
        net_change=change, change_percent=change_pct, volume=_integer(raw.get("volume")),
        exchange_timestamp=exchange_ts, received_at=received_at, quality=quality,
        year_high=_number(raw.get("year_high")),
        year_low=_number(raw.get("year_low")),
    )


class UpstoxV3QuoteProvider:
    """Upstox V3 Full Market Quotes snapshot adapter; token stays server-side."""
    provider_id = "upstox"

    def __init__(self, access_token: str, *, base_url: str = "https://api.upstox.com",
                 transport: httpx.AsyncBaseTransport | None = None):
        self._token = access_token.strip()
        self._base_url = base_url.rstrip("/")
        self._transport = transport

    @property
    def configured(self) -> bool:
        return bool(self._token)

    async def get_quotes(self, instruments: Sequence[QuoteInstrument]) -> list[MarketQuote]:
        if not self.configured:
            raise ProviderError(self.provider_id, "Upstox is not configured", retryable=False, status_code=503)
        keys = [item.upstox_instrument_key for item in instruments]
        if not keys or any(not key for key in keys):
            raise ProviderError(self.provider_id, "Every instrument needs an Upstox instrument key", retryable=False, status_code=422)
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(12.0, connect=5.0), transport=self._transport) as client:
                response = await client.get(
                    f"{self._base_url}/v3/market-quote/quotes",
                    params={"instrument_key": ",".join(str(key) for key in keys)},
                    headers={"Authorization": f"Bearer {self._token}", "Accept": "application/json"},
                )
        except httpx.TimeoutException as error:
            raise ProviderError(self.provider_id, "Upstox quote request timed out", retryable=True, status_code=503) from error
        except httpx.RequestError as error:
            raise ProviderError(self.provider_id, "Upstox quote service is unreachable", retryable=True, status_code=503) from error
        if response.status_code == 429 or response.status_code >= 500:
            raise ProviderError(self.provider_id, f"Upstox temporarily unavailable (HTTP {response.status_code})", retryable=True, status_code=503)
        if response.status_code in (401, 403):
            raise ProviderError(self.provider_id, "Upstox rejected the configured credential", retryable=False)
        if response.status_code >= 400:
            raise ProviderError(self.provider_id, f"Upstox rejected the quote request (HTTP {response.status_code})", retryable=False)
        try:
            payload = response.json()
        except ValueError as error:
            raise ProviderError(self.provider_id, "Upstox returned invalid JSON", retryable=False) from error
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict):
            raise ProviderError(self.provider_id, "Upstox response did not contain quote data", retryable=True)
        received_at = datetime.now(timezone.utc)
        symbols = {item.upstox_instrument_key: item.symbol for item in instruments}
        quotes = [
            _normalize_quote(symbols[key], self.provider_id, raw, received_at)
            for key, raw in data.items() if key in symbols and isinstance(raw, dict)
        ]
        if not quotes:
            raise ProviderError(self.provider_id, "Upstox returned no matching instrument quotes", retryable=True)
        return quotes


class DhanQuoteProvider:
    """Optional Dhan REST quote adapter; requires a user's eligible Data API access."""
    provider_id = "dhan"

    def __init__(self, client_id: str, access_token: str, *,
                 base_url: str = "https://api.dhan.co/v2",
                 transport: httpx.AsyncBaseTransport | None = None):
        self._client_id = client_id.strip()
        self._token = access_token.strip()
        self._base_url = base_url.rstrip("/")
        self._transport = transport

    @property
    def configured(self) -> bool:
        return bool(self._client_id and self._token)

    async def get_quotes(self, instruments: Sequence[QuoteInstrument]) -> list[MarketQuote]:
        if not self.configured:
            raise ProviderError(self.provider_id, "Dhan is not configured", retryable=False, status_code=503)
        grouped: dict[str, list[int]] = {}
        symbols: dict[tuple[str, str], str] = {}
        for item in instruments:
            if not item.dhan_exchange_segment or not item.dhan_security_id or not item.dhan_security_id.isdigit():
                raise ProviderError(self.provider_id, "Every instrument needs a Dhan exchange segment and numeric security ID", retryable=False, status_code=422)
            grouped.setdefault(item.dhan_exchange_segment, []).append(int(item.dhan_security_id))
            symbols[(item.dhan_exchange_segment, item.dhan_security_id)] = item.symbol
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(12.0, connect=5.0), transport=self._transport) as client:
                response = await client.post(
                    f"{self._base_url}/marketfeed/quote", json=grouped,
                    headers={"access-token": self._token, "client-id": self._client_id,
                             "Accept": "application/json", "Content-Type": "application/json"},
                )
        except httpx.TimeoutException as error:
            raise ProviderError(self.provider_id, "Dhan quote request timed out", retryable=True, status_code=503) from error
        except httpx.RequestError as error:
            raise ProviderError(self.provider_id, "Dhan quote service is unreachable", retryable=True, status_code=503) from error
        if response.status_code == 429 or response.status_code >= 500:
            raise ProviderError(self.provider_id, f"Dhan temporarily unavailable (HTTP {response.status_code})", retryable=True, status_code=503)
        if response.status_code in (401, 403):
            raise ProviderError(self.provider_id, "Dhan rejected the configured credential", retryable=False)
        if response.status_code >= 400:
            raise ProviderError(self.provider_id, f"Dhan rejected the quote request (HTTP {response.status_code})", retryable=False)
        try:
            payload = response.json()
        except ValueError as error:
            raise ProviderError(self.provider_id, "Dhan returned invalid JSON", retryable=False) from error
        data = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(data, dict):
            raise ProviderError(self.provider_id, "Dhan response did not contain quote data", retryable=True)
        received_at = datetime.now(timezone.utc)
        quotes = []
        for segment, values in data.items():
            if isinstance(values, dict):
                for security_id, raw in values.items():
                    symbol = symbols.get((segment, str(security_id)))
                    if symbol and isinstance(raw, dict):
                        quotes.append(_normalize_quote(symbol, self.provider_id, raw, received_at))
        if not quotes:
            raise ProviderError(self.provider_id, "Dhan returned no matching instrument quotes", retryable=True)
        return quotes
