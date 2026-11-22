from collections.abc import Sequence
from math import isfinite
from rovexquant.indicators.moving_average import ema
from rovexquant.indicators.volatility import true_range

def macd(closes: Sequence[float], fast: int = 12, slow: int = 26, signal: int = 9) -> tuple[list[float | None], list[float | None], list[float | None]]:
    if fast <= 0 or signal <= 0 or slow <= fast:
        raise ValueError("require 0 < fast < slow and signal > 0")
    if any(not isfinite(value) for value in closes):
        raise ValueError("prices must be finite")
    fast_line, slow_line = ema(closes, fast), ema(closes, slow)
    line: list[float | None] = [None] * len(closes)
    valid_values: list[float] = []
    valid_indices: list[int] = []
    for i, (f, s) in enumerate(zip(fast_line, slow_line, strict=True)):
        if f is not None and s is not None:
            value = f - s
            line[i] = value
            valid_values.append(value)
            valid_indices.append(i)
    signal_values = ema(valid_values, signal)
    signal_line: list[float | None] = [None] * len(closes)
    histogram: list[float | None] = [None] * len(closes)
    for index, value in zip(valid_indices, signal_values, strict=True):
        signal_line[index] = value
        if value is not None and line[index] is not None:
            histogram[index] = line[index] - value
    return line, signal_line, histogram

def adx(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int = 14) -> list[float | None]:
    if period <= 0:
        raise ValueError("period must be positive")
    if not (len(highs) == len(lows) == len(closes)):
        raise ValueError("high, low and close lengths must match")
    size = len(closes)
    out: list[float | None] = [None] * size
    if size <= 2 * period:
        return out
    ranges = true_range(highs, lows, closes)
    plus_dm = [0.0] * size
    minus_dm = [0.0] * size
    for i in range(1, size):
        up = highs[i] - highs[i - 1]
        down = lows[i - 1] - lows[i]
        plus_dm[i] = up if up > down and up > 0 else 0.0
        minus_dm[i] = down if down > up and down > 0 else 0.0
    tr_sum = sum(value or 0.0 for value in ranges[1:period + 1])
    plus_sum, minus_sum = sum(plus_dm[1:period + 1]), sum(minus_dm[1:period + 1])
    dx_values: list[float] = []
    dx_indices: list[int] = []
    for i in range(period, size):
        if i > period:
            current_tr = ranges[i] or 0.0
            tr_sum = tr_sum - tr_sum / period + current_tr
            plus_sum = plus_sum - plus_sum / period + plus_dm[i]
            minus_sum = minus_sum - minus_sum / period + minus_dm[i]
        plus_di = 100.0 * plus_sum / tr_sum if tr_sum else 0.0
        minus_di = 100.0 * minus_sum / tr_sum if tr_sum else 0.0
        denominator = plus_di + minus_di
        dx = 100.0 * abs(plus_di - minus_di) / denominator if denominator else 0.0
        dx_values.append(dx)
        dx_indices.append(i)
    if len(dx_values) < period:
        return out
    current_adx = sum(dx_values[:period]) / period
    out[dx_indices[period - 1]] = current_adx
    for j in range(period, len(dx_values)):
        current_adx = ((period - 1) * current_adx + dx_values[j]) / period
        out[dx_indices[j]] = current_adx
    return out
