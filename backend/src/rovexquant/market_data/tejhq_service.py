"""Application services for the TejHQ EOD provider adapter."""
from datetime import date, datetime, timedelta
import math
from typing import Any
from zoneinfo import ZoneInfo

from rovexquant.market_data.tejhq import TejHqEodProvider, TejHqError as TejHqError


def provider_status() -> dict[str, Any]:
    return TejHqEodProvider.from_environment().safe_status()


async def fetch_ohlcv(exchange: str, symbol: str, from_date: date, to_date: date, *, adjusted: bool):
    provider = TejHqEodProvider.from_environment()
    bars = await provider.get_ohlcv(exchange, symbol, from_date, to_date, adjusted=adjusted)
    return provider.provider_id, bars


async def fetch_delivery(exchange: str, symbol: str, from_date: date, to_date: date):
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_delivery(exchange, symbol, from_date, to_date)


async def fetch_snapshot(exchange: str, trading_date: date):
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_snapshot(exchange, trading_date)


async def fetch_actions(symbol: str):
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_actions(symbol)


async def fetch_status():
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_status()


def _finite_number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(str(value).replace(",", "").strip())
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


async def fetch_latest_snapshot(
    exchange: str,
    *,
    max_lookback_days: int = 10,
    as_of: date | None = None,
) -> tuple[str, date, list[dict[str, Any]]]:
    """Fetch the most recent available EOD snapshot without guessing a trading day."""
    if not 1 <= max_lookback_days <= 14:
        raise TejHqError("Snapshot lookback must be between 1 and 14 days", 422)
    provider = TejHqEodProvider.from_environment()
    anchor = as_of or datetime.now(ZoneInfo("Asia/Kolkata")).date()
    for offset in range(max_lookback_days + 1):
        trading_date = anchor - timedelta(days=offset)
        try:
            rows = await provider.get_snapshot(exchange, trading_date)
        except TejHqError as error:
            if error.status_code in (400, 404) and not error.retryable:
                continue
            raise
        if rows:
            return provider.provider_id, trading_date, rows
    raise TejHqError(
        f"No {exchange.upper()} EOD snapshot was available in the last {max_lookback_days} days",
        503,
    )


def summarize_snapshot_rows(
    rows: list[dict[str, Any]],
    *,
    limit: int = 20,
    allowed_series: frozenset[str] | set[str] | None = frozenset({"EQ"}),
) -> dict[str, Any]:
    """Calculate transparent market breadth and movers from source EOD rows."""
    if not 1 <= limit <= 50:
        raise TejHqError("Snapshot summary limit must be between 1 and 50", 422)

    priced: list[dict[str, Any]] = []
    active: list[dict[str, Any]] = []
    advances = declines = unchanged = 0

    for row in rows:
        if not isinstance(row, dict):
            continue
        series = str(row.get("series") or "").upper()
        if allowed_series is not None and series and series not in allowed_series:
            continue
        symbol = str(row.get("symbol") or row.get("symbol_code") or "").strip().upper()
        if not symbol:
            continue
        close = _finite_number(row.get("close"))
        previous_close = _finite_number(row.get("prev_close", row.get("previous_close")))
        volume_value = _finite_number(row.get("volume"))
        volume = max(0, int(volume_value)) if volume_value is not None else 0
        turnover = _finite_number(row.get("turnover"))

        base = {
            "symbol": symbol,
            "name": str(row.get("name") or row.get("company_name") or symbol)[:160],
            "series": series or None,
            "close": close,
            "volume": volume,
            "turnover": turnover,
        }
        if close is not None and volume > 0:
            active.append(base)
        if close is None or previous_close is None or previous_close <= 0:
            continue

        change = close - previous_close
        change_pct = (change / previous_close) * 100.0
        if not math.isfinite(change_pct):
            continue
        if change > 0:
            advances += 1
        elif change < 0:
            declines += 1
        else:
            unchanged += 1
        priced.append({
            **base,
            "previous_close": previous_close,
            "change": change,
            "change_pct": change_pct,
        })

    gainers = sorted(
        (item for item in priced if item["change_pct"] > 0),
        key=lambda item: item["change_pct"],
        reverse=True,
    )[:limit]
    losers = sorted(
        (item for item in priced if item["change_pct"] < 0),
        key=lambda item: item["change_pct"],
    )[:limit]
    most_active = sorted(active, key=lambda item: item["volume"], reverse=True)[:limit]

    return {
        "total_rows": len(rows),
        "equity_series_rows": advances + declines + unchanged,
        "priced_symbols": len(priced),
        "advances": advances,
        "declines": declines,
        "unchanged": unchanged,
        "top_gainers": gainers,
        "top_losers": losers,
        "most_active_by_volume": most_active,
    }
