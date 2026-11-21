from collections.abc import Sequence
from math import isfinite
from rovexquant.strategies.base import StrategyMetadata

class DonchianBreakout:
    metadata = StrategyMetadata("breakout.donchian", "Donchian breakout", "1.0.0", "swing", "Close crosses the prior N-bar high/low channel.")

    def __init__(self, period: int = 20):
        if period <= 1:
            raise ValueError("period must be greater than one")
        self.period = period

    def evaluate(self, highs: Sequence[float], lows: Sequence[float], closes: Sequence[float]) -> list[str]:
        if not (len(highs) == len(lows) == len(closes)):
            raise ValueError("high, low and close lengths must match")
        for high, low, close in zip(highs, lows, closes, strict=True):
            if not all(isfinite(value) for value in (high, low, close)) or low <= 0 or not low <= close <= high:
                raise ValueError("OHLC inputs must be finite, positive and ordered")
        out = ["INSUFFICIENT_HISTORY"] * len(closes)
        for index in range(self.period, len(closes)):
            upper = max(highs[index - self.period:index])
            lower = min(lows[index - self.period:index])
            prior_start = max(0, index - self.period - 1)
            prior_upper = max(highs[prior_start:index - 1])
            prior_lower = min(lows[prior_start:index - 1])
            if closes[index] > upper and closes[index - 1] <= prior_upper:
                out[index] = "BULLISH_BREAKOUT"
            elif closes[index] < lower and closes[index - 1] >= prior_lower:
                out[index] = "BEARISH_BREAKOUT"
            else:
                out[index] = "NO_BREAKOUT"
        return out
