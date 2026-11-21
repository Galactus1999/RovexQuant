from collections.abc import Sequence
from rovexquant.indicators.momentum import rsi
from rovexquant.strategies.base import StrategyMetadata

class RsiRegime:
    metadata = StrategyMetadata("momentum.rsi_regime", "RSI regime", "1.0.0", "swing", "RSI context flag; not standalone entry logic.")
    def __init__(self, period: int = 14, oversold: float = 30, overbought: float = 70):
        if period <= 0 or not 0 <= oversold < overbought <= 100: raise ValueError("invalid RSI parameters")
        self.period, self.oversold, self.overbought = period, oversold, overbought
    def evaluate(self, closes: Sequence[float]) -> str:
        values = rsi(closes, self.period)
        current = values[-1] if values else None
        if current is None: return "INSUFFICIENT_HISTORY"
        if current <= self.oversold: return "OVERSOLD_CONTEXT"
        if current >= self.overbought: return "OVERBOUGHT_CONTEXT"
        return "NEUTRAL"
