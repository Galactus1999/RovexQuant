from collections.abc import Sequence
from rovexquant.indicators.moving_average import ema, sma
from rovexquant.strategies.base import StrategyMetadata

class SmaCrossover:
    metadata = StrategyMetadata("trend.sma_crossover", "SMA crossover", "1.0.0", "swing", "Fast/slow SMA cross research candidate.")
    def __init__(self, fast: int = 20, slow: int = 50):
        if fast <= 0 or slow <= fast: raise ValueError("require 0 < fast < slow")
        self.fast, self.slow = fast, slow
    def evaluate(self, closes: Sequence[float]) -> str:
        if len(closes) < self.slow + 1: return "INSUFFICIENT_HISTORY"
        f, s = sma(closes, self.fast), sma(closes, self.slow)
        if f[-2] <= s[-2] and f[-1] > s[-1]: return "BULLISH_CROSS"
        if f[-2] >= s[-2] and f[-1] < s[-1]: return "BEARISH_CROSS"
        return "NO_CROSS"

class EmaTrend:
    metadata = StrategyMetadata("trend.ema_alignment", "EMA alignment", "1.0.0", "swing", "Fast/slow EMA alignment.")
    def __init__(self, fast: int = 12, slow: int = 26):
        if fast <= 0 or slow <= fast: raise ValueError("require 0 < fast < slow")
        self.fast, self.slow = fast, slow
    def evaluate(self, closes: Sequence[float]) -> str:
        f, s = ema(closes, self.fast), ema(closes, self.slow)
        if not closes or f[-1] is None or s[-1] is None: return "INSUFFICIENT_HISTORY"
        return "BULLISH_TREND" if f[-1] > s[-1] else "BEARISH_TREND" if f[-1] < s[-1] else "NEUTRAL"
