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
        if not all(isfinite(value) for value in (high, low, close)) or low <= 0 or not low <= close <= high:
            raise ValueError("OHLC inputs must be finite, positive and ordered")
        if i:
            out[i] = max(high - low, abs(high - closes[i - 1]), abs(low - closes[i - 1]))
    return out

def atr(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int = 14) -> list[float | None]:
    _validate_period(period)
    ranges = true_range(highs, lows, closes)
    out: list[float | None] = [None] * len(closes)
    valid = [value for value in ranges[1:] if value is not None]
    if len(valid) < period:
        return out
    previous = sum(valid[:period]) / period
    out[period] = previous
    for index in range(period + 1, len(closes)):
        current = ranges[index]
        assert current is not None
        previous = ((period - 1) * previous + current) / period
        out[index] = previous
    return out

def bollinger_bands(closes: Sequence[float], period: int = 20, deviations: float = 2.0) -> tuple[list[float | None], list[float | None], list[float | None]]:
    if period <= 1 or not isfinite(deviations) or deviations <= 0:
        raise ValueError("period must exceed one and deviations must be finite and positive")
    if any(not isfinite(value) or value <= 0 for value in closes):
        raise ValueError("prices must be finite and positive")
    upper: list[float | None] = [None] * len(closes)
    middle: list[float | None] = [None] * len(closes)
    lower: list[float | None] = [None] * len(closes)
    for index in range(period - 1, len(closes)):
        window = closes[index - period + 1:index + 1]
        mean = sum(window) / period
        variance = sum((value - mean) ** 2 for value in window) / period
        width = deviations * variance ** 0.5
        middle[index] = mean
        upper[index] = mean + width
        lower[index] = mean - width
    return upper, middle, lower
