from collections.abc import Sequence
from math import isfinite

def session_vwap(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], volumes: Sequence[float], sessions: Sequence[str]) -> list[float | None]:
    size = len(closes)
    if not all(len(values) == size for values in (highs, lows, volumes, sessions)):
        raise ValueError("all inputs must have equal lengths")
    out: list[float | None] = [None] * size
    session = None
    cumulative_pv = cumulative_volume = 0.0
    for index in range(size):
        high, low, close, volume = highs[index], lows[index], closes[index], volumes[index]
        if not all(isfinite(value) for value in (high, low, close, volume)) or low <= 0 or not low <= close <= high or volume < 0:
            raise ValueError("prices and volume must be finite, ordered and non-negative")
        if not sessions[index]:
            raise ValueError("session identifiers must be non-empty")
        if sessions[index] != session:
            session = sessions[index]
            cumulative_pv = cumulative_volume = 0.0
        typical_price = (high + low + close) / 3.0
        cumulative_pv += typical_price * volume
        cumulative_volume += volume
        if cumulative_volume > 0:
            out[index] = cumulative_pv / cumulative_volume
    return out

def opening_range_breakout(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], sessions: Sequence[str], opening_bars: int = 3) -> list[str]:
    size = len(closes)
    if not all(len(values) == size for values in (highs, lows, sessions)):
        raise ValueError("all inputs must have equal lengths")
    if opening_bars <= 0:
        raise ValueError("opening_bars must be positive")
    out = ["NO_SIGNAL"] * size
    current_session = None
    session_high, session_low, count = float("-inf"), float("inf"), 0
    previous_close = None
    for index in range(size):
        if not sessions[index]:
            raise ValueError("session identifiers must be non-empty")
        if sessions[index] != current_session:
            current_session = sessions[index]
            session_high, session_low, count = float("-inf"), float("inf"), 0
            previous_close = None
        high, low, close = highs[index], lows[index], closes[index]
        if not all(isfinite(value) for value in (high, low, close)) or low <= 0 or not low <= close <= high:
            raise ValueError("OHLC inputs must be finite, positive and ordered")
        if count < opening_bars:
            session_high, session_low = max(session_high, high), min(session_low, low)
            count += 1
        elif previous_close is not None:
            if close > session_high and previous_close <= session_high:
                out[index] = "BULLISH_BREAKOUT"
            elif close < session_low and previous_close >= session_low:
                out[index] = "BEARISH_BREAKOUT"
        previous_close = close
    return out
