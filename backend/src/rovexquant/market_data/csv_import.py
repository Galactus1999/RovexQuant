import csv
import io
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from uuid import UUID
from rovexquant.domain.market import DataQuality, MarketBar

_TIMEFRAMES = {
    "1m": timedelta(minutes=1), "5m": timedelta(minutes=5),
    "15m": timedelta(minutes=15), "30m": timedelta(minutes=30),
    "1h": timedelta(hours=1), "1d": timedelta(days=1), "1w": timedelta(weeks=1),
}
_REQUIRED = {"timestamp", "open", "high", "low", "close", "volume"}

def import_ohlcv_csv(
    content: str,
    instrument_id: UUID,
    timeframe: str,
    *,
    received_at: datetime | None = None,
    max_rows: int = 1_000_000,
) -> list[MarketBar]:
    """Import explicitly supplied, licensed OHLCV CSV. Timestamps must include an offset."""
    if timeframe not in _TIMEFRAMES:
        raise ValueError("unsupported timeframe")
    if max_rows <= 0:
        raise ValueError("max_rows must be positive")
    received_at = received_at or datetime.now(timezone.utc)
    if received_at.tzinfo is None or received_at.utcoffset() is None:
        raise ValueError("received_at must be timezone-aware")
    reader = csv.DictReader(io.StringIO(content))
    if reader.fieldnames is None or not _REQUIRED.issubset(set(reader.fieldnames)):
        raise ValueError("CSV must contain timestamp, open, high, low, close and volume columns")
    result: list[MarketBar] = []
    previous_start = None
    for row_number, row in enumerate(reader, start=2):
        if row_number > max_rows + 1:
            raise ValueError("CSV exceeds configured row limit")
        if not row or all(not (value or "").strip() for value in row.values()):
            continue
        try:
            start_at = datetime.fromisoformat(row["timestamp"].strip().replace("Z", "+00:00"))
            if start_at.tzinfo is None or start_at.utcoffset() is None:
                raise ValueError("timestamp must include timezone offset")
            open_price = Decimal(row["open"])
            high = Decimal(row["high"])
            low = Decimal(row["low"])
            close = Decimal(row["close"])
            volume_decimal = Decimal(row["volume"])
        except (AttributeError, KeyError, InvalidOperation, ValueError) as error:
            raise ValueError(f"invalid CSV row {row_number}: {error}") from error
        if not volume_decimal.is_finite() or volume_decimal < 0 or volume_decimal != volume_decimal.to_integral_value():
            raise ValueError(f"invalid volume at CSV row {row_number}")
        quality = DataQuality.VALID
        if previous_start is not None and start_at.date() == previous_start.date() and timeframe in {"1m", "5m", "15m", "30m", "1h"}:
            interval = _TIMEFRAMES[timeframe]
            elapsed = start_at - previous_start
            if elapsed < interval:
                raise ValueError(f"overlapping or irregular intraday candles at CSV row {row_number}")
            if elapsed > interval:
                quality = DataQuality.GAP_DETECTED
        bar = MarketBar(
            instrument_id=instrument_id,
            timeframe=timeframe,
            start_at=start_at,
            end_at=start_at + _TIMEFRAMES[timeframe],
            open=open_price,
            high=high,
            low=low,
            close=close,
            volume=int(volume_decimal),
            provider="user_supplied_csv",
            exchange_timestamp=start_at + _TIMEFRAMES[timeframe],
            received_at=received_at,
            is_complete=True,
            quality=quality,
        )
        if not bar.valid_ohlc():
            raise ValueError(f"invalid OHLC values at CSV row {row_number}")
        if previous_start is not None and start_at <= previous_start:
            raise ValueError("CSV timestamps must be strictly increasing without duplicates")
        previous_start = start_at
        result.append(bar)
    if not result:
        raise ValueError("CSV contains no data rows")
    return result
