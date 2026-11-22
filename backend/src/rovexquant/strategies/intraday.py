from collections.abc import Sequence
from rovexquant.indicators.intraday import opening_range_breakout, session_vwap
from rovexquant.strategies.base import StrategyMetadata

class OpeningRangeBreakout:
    metadata = StrategyMetadata("intraday.opening_range", "Opening range breakout", "1.0.0", "intraday", "Breakout after a configurable number of opening bars.")
    def __init__(self, opening_bars: int = 3):
        if opening_bars <= 0:
            raise ValueError("opening_bars must be positive")
        self.opening_bars = opening_bars

    def evaluate(self, highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], sessions: Sequence[str]) -> list[str]:
        return opening_range_breakout(highs, lows, closes, sessions, self.opening_bars)

class SessionVwapCross:
    metadata = StrategyMetadata("intraday.session_vwap_cross", "Session VWAP cross", "1.0.0", "intraday", "Close crosses session-anchored cumulative HLC3 VWAP.")
    def evaluate(self, highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], volumes: Sequence[float], sessions: Sequence[str]) -> list[str]:
        vwap = session_vwap(highs, lows, closes, volumes, sessions)
        if len(closes) != len(sessions):
            raise ValueError("all inputs must have equal lengths")
        out = ["NO_SIGNAL"] * len(closes)
        for i in range(1, len(closes)):
            if sessions[i] != sessions[i - 1] or vwap[i] is None or vwap[i - 1] is None:
                continue
            if closes[i - 1] <= vwap[i - 1] and closes[i] > vwap[i]:
                out[i] = "BULLISH_CROSS"
            elif closes[i - 1] >= vwap[i - 1] and closes[i] < vwap[i]:
                out[i] = "BEARISH_CROSS"
        return out
