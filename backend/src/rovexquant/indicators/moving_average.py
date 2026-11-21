from collections.abc import Sequence
from math import isfinite

def _validate(values: Sequence[float], period: int) -> None:
    if period <= 0:
        raise ValueError("period must be positive")
    if any(not isfinite(value) for value in values):
        raise ValueError("values must be finite")

def sma(values: Sequence[float], period: int) -> list[float | None]:
    _validate(values, period)
    out: list[float | None] = [None] * len(values)
    total = 0.0
    for i, value in enumerate(values):
        total += value
        if i >= period:
            total -= values[i - period]
        if i >= period - 1:
            out[i] = total / period
    return out

def ema(values: Sequence[float], period: int) -> list[float | None]:
    _validate(values, period)
    out: list[float | None] = [None] * len(values)
    if len(values) < period:
        return out
    alpha = 2.0 / (period + 1)
    previous = sum(values[:period]) / period
    out[period - 1] = previous
    for i in range(period, len(values)):
        previous = alpha * values[i] + (1 - alpha) * previous
        out[i] = previous
    return out
