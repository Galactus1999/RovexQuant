from collections.abc import Sequence
from math import isfinite
from rovexquant.strategies.base import StrategyMetadata

class DonchianBreakout:
    metadata = StrategyMetadata("breakout.donchian", "Donchian breakout", "1.0.0", "swing", "Close breaks the prior N-bar high/low channel.")
    def __init__(self, period: int = 20):
        if period <= 1:
            raise ValueError("period must be greater than one")
        self.period = period

    def evaluate(self, highs: Sequence[float], lows: Sequence[float], closes: Sequence[float]) -> list[str]:
        if not (len(highs) == len(lows) == len(closes)):
            raise ValueError("high, low and close lengths must match")
        out = ["INSUFFICIENT_HISTORY"] * len(closes)
        for i in range(self.period, len(closes)):
            window_high = highs[i - self.period:i]
            window_low = lows[i - self.period:i]
            current = (highs[i], lows[i], closes[i], *window_high, *window_low)
            if not all(isfinite(value) for value in current):
                raise ValueError("OHLC inputs must be finite")
            if closes[i] > max(window_high):
                out[i] = "BULLISH_BREAKOUT"
            elif closes[i] < min(window_low):
                out[i] = "BEARISH_BREAKOUT"
            else:
                out[i] = "NO_BREAKOUT"
        return out
