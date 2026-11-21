from collections.abc import Sequence
from math import isfinite

def session_vwap(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], volumes: Sequence[float], sessions: Sequence[str]) -> list[float | None]:
    size = len(closes)
    if not all(len(values) == size for values in (highs, lows, volumes, sessions)):
        raise ValueError("all inputs must have equal lengths")
    out: list[float | None] = [None] * size
    session = None
    cumulative_pv = 0.0
    cumulative_volume = 0.0
    for i in range(size):
        high, low, close, volume = highs[i], lows[i], closes[i], volumes[i]
        if not all(isfinite(value) for value in (high, low, close, volume)) or low <= 0 or high < low or volume < 0:
            raise ValueError("prices and volume must be finite, positive/ordered and non-negative")
        if not sessions[i]:
            raise ValueError("session identifiers must be non-empty")
        if sessions[i] != session:
            session = sessions[i]
            cumulative_pv = 0.0
            cumulative_volume = 0.0
        typical_price = (high + low + close) / 3.0
        cumulative_pv += typical_price * volume
        cumulative_volume += volume
        if cumulative_volume > 0:
            out[i] = cumulative_pv / cumulative_volume
    return out

def opening_range_breakout(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], sessions: Sequence[str], opening_bars: int = 3) -> list[str]:
    size = len(closes)
    if not all(len(values) == size for values in (highs, lows, sessions)):
        raise ValueError("all inputs must have equal lengths")
    if opening_bars <= 0:
        raise ValueError("opening_bars must be positive")
    out = ["NO_SIGNAL"] * size
    current_session = None
    session_high = float("-inf")
    session_low = float("inf")
    count = 0
    for i in range(size):
        if not sessions[i]:
            raise ValueError("session identifiers must be non-empty")
        if sessions[i] != current_session:
            current_session = sessions[i]
            session_high, session_low, count = float("-inf"), float("inf"), 0
        if not all(isfinite(value) for value in (highs[i], lows[i], closes[i])) or lows[i] <= 0 or highs[i] < lows[i]:
            raise ValueError("OHLC inputs must be finite, positive and ordered")
        if count < opening_bars:
            session_high = max(session_high, highs[i])
            session_low = min(session_low, lows[i])
            count += 1
            continue
        if closes[i] > session_high:
            out[i] = "BULLISH_BREAKOUT"
        elif closes[i] < session_low:
            out[i] = "BEARISH_BREAKOUT"
    return out
