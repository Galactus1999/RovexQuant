"""Explicitly opt-in Yahoo Finance adapter for local personal research only."""
from __future__ import annotations
from datetime import date, datetime
import math
import os
from typing import Any


class YahooResearchError(RuntimeError):
    pass


def _missing(value: Any) -> bool:
    if value is None:
        return True
    try:
        return math.isnan(float(value))
    except (TypeError, ValueError):
        return False


def download_personal_research_history(
    symbol: str, *, start: date, end: date, interval: str = "1d",
    yfinance_module: Any | None = None,
) -> list[dict[str, Any]]:
    """Fetch an explicit personal-research series; never auto-schedule it."""
    if os.getenv("QUANT_ENABLE_YAHOO_PERSONAL_RESEARCH") != "1":
        raise YahooResearchError("Yahoo extraction is disabled; explicit private opt-in required")
    if start >= end or (end - start).days > 6200:
        raise YahooResearchError("Yahoo date range must be ordered and at most 17 years")
    if interval not in {"1d", "1wk", "1mo"}:
        raise YahooResearchError("Only daily, weekly and monthly Yahoo intervals are enabled")
    ticker = symbol.strip()
    if not ticker or len(ticker) > 40 or any(ch.isspace() for ch in ticker):
        raise YahooResearchError("Invalid Yahoo ticker")
    if yfinance_module is None:
        try:
            import yfinance as yfinance_module
        except ImportError as error:
            raise YahooResearchError(
                "Optional yfinance package is not installed in this local environment"
            ) from error
    try:
        frame = yfinance_module.download(
            ticker, start=start.isoformat(), end=end.isoformat(), interval=interval,
            auto_adjust=False, actions=True, progress=False, threads=False,
            multi_level_index=False,
        )
    except Exception as error:
        raise YahooResearchError("Yahoo personal-research request failed") from error
    if frame is None or frame.empty:
        return []
    required = {"Open", "High", "Low", "Close", "Volume"}
    if not required.issubset(set(frame.columns)):
        raise YahooResearchError("Yahoo response is missing required OHLCV columns")

    output: list[dict[str, Any]] = []
    for index, row in frame.iterrows():
        names = ("Open", "High", "Low", "Close")
        if any(_missing(row[name]) for name in names) or _missing(row["Volume"]):
            continue
        opening, high, low, close = (float(row[name]) for name in names)
        volume = float(row["Volume"])
        if min(opening, high, low, close) <= 0 or (
            low > min(opening, close) or high < max(opening, close) or low > high
        ):
            raise YahooResearchError("Yahoo returned inconsistent OHLC values")
        if volume < 0 or not volume.is_integer():
            raise YahooResearchError("Yahoo returned invalid volume")
        stamp = index.to_pydatetime() if hasattr(index, "to_pydatetime") else index
        if isinstance(stamp, datetime):
            stamp = stamp.date()
        record = {
            "date": stamp.isoformat(), "symbol": ticker, "open": opening,
            "high": high, "low": low, "close": close, "volume": int(volume),
            "adjusted_close": None, "dividends": 0.0, "stock_splits": 0.0,
            "price_adjustment": "UNADJUSTED_OHLC_WITH_SEPARATE_ADJ_CLOSE",
            "data_mode": "THIRD_PARTY_HISTORICAL_RESEARCH",
        }
        if "Adj Close" in frame.columns and not _missing(row["Adj Close"]):
            record["adjusted_close"] = float(row["Adj Close"])
        if "Dividends" in frame.columns and not _missing(row["Dividends"]):
            record["dividends"] = float(row["Dividends"])
        if "Stock Splits" in frame.columns and not _missing(row["Stock Splits"]):
            record["stock_splits"] = float(row["Stock Splits"])
        output.append(record)
    return output
