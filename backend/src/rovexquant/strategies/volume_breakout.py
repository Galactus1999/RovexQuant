from collections.abc import Sequence
from math import isfinite
from rovexquant.indicators.volume import relative_volume
from rovexquant.strategies.base import StrategyMetadata
from rovexquant.strategies.breakout import DonchianBreakout

class VolumeConfirmedBreakout:
    metadata = StrategyMetadata("breakout.volume_confirmed", "Volume-confirmed breakout", "1.0.0", "swing", "Prior-channel breakout confirmed by relative volume.")

    def __init__(self, channel_period: int = 20, volume_period: int = 20, minimum_relative_volume: float = 1.5):
        if not isfinite(minimum_relative_volume) or not 0 < minimum_relative_volume:
            raise ValueError("minimum_relative_volume must be positive")
        self.channel = DonchianBreakout(channel_period)
        self.volume_period = volume_period
        self.minimum_relative_volume = minimum_relative_volume

    def evaluate(self, highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], volumes: Sequence[float]) -> list[str]:
        if len(volumes) != len(closes):
            raise ValueError("volume and price lengths must match")
        breakouts = self.channel.evaluate(highs, lows, closes)
        relative = relative_volume(volumes, self.volume_period)
        result = ["INSUFFICIENT_HISTORY"] * len(closes)
        for index, signal in enumerate(breakouts):
            if signal in ("BULLISH_BREAKOUT", "BEARISH_BREAKOUT") and relative[index] is not None:
                result[index] = signal if relative[index] >= self.minimum_relative_volume else "BREAKOUT_UNCONFIRMED"
            else:
                result[index] = signal
        return result
