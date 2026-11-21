from collections.abc import Sequence
from math import isfinite

def _validate_period(period: int) -> None:
    if period <= 0:
        raise ValueError("period must be positive")

def true_range(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float]) -> list[float | None]:
    if not (len(highs) == len(lows) == len(closes)):
        raise ValueError("high, low and close lengths must match")
    out: list[float | None] = [None] * len(closes)
    for i, (high, low, close) in enumerate(zip(highs, lows, closes, strict=True)):
        if not all(isfinite(x) for x in (high, low, close)) or low <= 0 or high < low:
            raise ValueError("OHLC inputs must be finite, positive and ordered")
        if i:
            previous = closes[i - 1]
            out[i] = max(high - low, abs(high - previous), abs(low - previous))
    return out

def atr(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int = 14) -> list[float | None]:
    _validate_period(period)
    ranges = true_range(highs, lows, closes)
    out: list[float | None] = [None] * len(closes)
    valid = [value for value in ranges[1:] if value is not None]
    if len(valid) < period:
        return out
    seed_index = period
    previous = sum(valid[:period]) / period
    out[seed_index] = previous
    for i in range(seed_index + 1, len(closes)):
        current = ranges[i]
        assert current is not None
        previous = ((period - 1) * previous + current) / period
        out[i] = previous
    return out
