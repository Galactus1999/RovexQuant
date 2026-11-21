from collections.abc import Sequence
from math import isfinite

def rsi(closes: Sequence[float], period: int = 14) -> list[float | None]:
    if period <= 0:
        raise ValueError("period must be positive")
    if any(not isfinite(value) for value in closes):
        raise ValueError("prices must be finite")
    out: list[float | None] = [None] * len(closes)
    if len(closes) <= period:
        return out
    changes = [closes[i] - closes[i - 1] for i in range(1, len(closes))]
    gains = [max(value, 0.0) for value in changes]
    losses = [max(-value, 0.0) for value in changes]
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period

    def calculate(gain: float, loss: float) -> float:
        if loss == 0:
            return 100.0 if gain > 0 else 50.0
        if gain == 0:
            return 0.0
        return 100.0 - 100.0 / (1.0 + gain / loss)

    out[period] = calculate(avg_gain, avg_loss)
    for i in range(period + 1, len(closes)):
        change_index = i - 1
        avg_gain = ((period - 1) * avg_gain + gains[change_index]) / period
        avg_loss = ((period - 1) * avg_loss + losses[change_index]) / period
        out[i] = calculate(avg_gain, avg_loss)
    return out
