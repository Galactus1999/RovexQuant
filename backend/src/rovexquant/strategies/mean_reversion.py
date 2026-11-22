from collections.abc import Sequence
from rovexquant.indicators.volatility import bollinger_bands
from rovexquant.strategies.base import StrategyMetadata

class BollingerReentry:
    metadata = StrategyMetadata("mean_reversion.bollinger_reentry", "Bollinger re-entry", "1.0.0", "swing", "Close re-enters the prior outside-band region; context only.")

    def __init__(self, period: int = 20, deviations: float = 2.0):
        self.period = period
        self.deviations = deviations
        # Validate parameters at construction.
        bollinger_bands([], period, deviations)

    def evaluate(self, closes: Sequence[float]) -> list[str]:
        upper, _, lower = bollinger_bands(closes, self.period, self.deviations)
        result = ["INSUFFICIENT_HISTORY"] * len(closes)
        for index in range(1, len(closes)):
            if lower[index - 1] is None or lower[index] is None or upper[index - 1] is None or upper[index] is None:
                continue
            if closes[index - 1] < lower[index - 1] and closes[index] >= lower[index]:
                result[index] = "BULLISH_REENTRY"
            elif closes[index - 1] > upper[index - 1] and closes[index] <= upper[index]:
                result[index] = "BEARISH_REENTRY"
            else:
                result[index] = "NO_SIGNAL"
        return result
