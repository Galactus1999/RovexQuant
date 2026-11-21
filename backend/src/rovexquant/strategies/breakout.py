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
        for index in range(self.period, len(closes)):
            window_high = highs[index - self.period:index]
            window_low = lows[index - self.period:index]
            high, low, close = highs[index], lows[index], closes[index]
            if not all(isfinite(value) for value in (high, low, close, *window_high, *window_low)):
                raise ValueError("OHLC inputs must be finite")
            if low <= 0 or not low <= close <= high or any(lo <= 0 or hi < lo for hi, lo in zip(window_high, window_low, strict=True)):
                raise ValueError("OHLC inputs must be positive and ordered")
            if close > max(window_high):
                out[index] = "BULLISH_BREAKOUT"
            elif close < min(window_low):
                out[index] = "BEARISH_BREAKOUT"
            else:
                out[index] = "NO_BREAKOUT"
        return out
