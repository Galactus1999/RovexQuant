from collections.abc import Sequence
from math import isfinite

def on_balance_volume(closes: Sequence[float], volumes: Sequence[float]) -> list[float]:
    if len(closes) != len(volumes):
        raise ValueError("close and volume lengths must match")
    result: list[float] = []
    total = 0.0
    previous = None
    for close, volume in zip(closes, volumes, strict=True):
        if not isfinite(close) or close <= 0 or not isfinite(volume) or volume < 0:
            raise ValueError("prices and volumes must be finite and valid")
        if previous is not None:
            if close > previous:
                total += volume
            elif close < previous:
                total -= volume
        result.append(total)
        previous = close
    return result

def relative_volume(volumes: Sequence[float], period: int = 20) -> list[float | None]:
    if period <= 0:
        raise ValueError("period must be positive")
    if any(not isfinite(value) or value < 0 for value in volumes):
        raise ValueError("volumes must be finite and non-negative")
    result: list[float | None] = [None] * len(volumes)
    for index in range(period, len(volumes)):
        baseline = sum(volumes[index - period:index]) / period
        if baseline > 0:
            result[index] = volumes[index] / baseline
    return result
