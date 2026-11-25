import pytest
from rovexquant.indicators.intraday import opening_range_breakout, session_vwap
from rovexquant.indicators.trend import adx, macd
from rovexquant.indicators.volatility import atr
from rovexquant.strategies.breakout import DonchianBreakout

def test_atr_wilder_seed_and_value():
    values = atr([11, 12, 13], [9, 10, 11], [10, 11, 12], period=2)
    assert values[:2] == [None, None]
    assert values[2] == pytest.approx(2.0)

def test_adx_trending_series_warms_up():
    highs = [10, 11, 12, 13, 14, 15, 16]
    lows = [8, 9, 10, 11, 12, 13, 14]
    closes = [9, 10, 11, 12, 13, 14, 15]
    values = adx(highs, lows, closes, period=2)
    assert values[2] is None
    assert values[3] == pytest.approx(100.0)

def test_macd_has_warmup_and_histogram():
    line, signal, histogram = macd([float(i) for i in range(1, 80)])
    assert line[24] is None
    assert signal[32] is None
    assert histogram[-1] == pytest.approx(0.0, abs=1e-3)

def test_session_vwap_resets_and_ignores_zero_volume():
    values = session_vwap([11, 12, 21], [9, 10, 19], [10, 11, 20], [10, 10, 10], ["A", "A", "B"])
    assert values[0] == pytest.approx(10.0)
    assert values[1] == pytest.approx(10.5)
    assert values[2] == pytest.approx(20.0)

def test_opening_range_uses_only_completed_opening_bars():
    signals = opening_range_breakout([10, 11, 12, 13], [9, 10, 10, 12], [9.5, 10.5, 10.5, 12.5], ["A"] * 4, 2)
    assert signals == ["NO_SIGNAL", "NO_SIGNAL", "NO_SIGNAL", "BULLISH_BREAKOUT"]

def test_donchian_excludes_current_bar_from_channel():
    strategy = DonchianBreakout(period=2)
    signals = strategy.evaluate([10, 11, 12, 13], [8, 9, 10, 11], [9, 10, 11, 13])
    assert signals == ["INSUFFICIENT_HISTORY", "INSUFFICIENT_HISTORY", "INSUFFICIENT_HISTORY", "BULLISH_BREAKOUT"]

def test_indicator_rejects_misaligned_inputs():
    with pytest.raises(ValueError):
        atr([10, 11], [9], [9.5, 10], period=1)

def test_opening_range_emits_only_first_breakout_crossing():
    signals = opening_range_breakout(
        [10, 11, 12, 13, 14], [9, 10, 10, 11, 12], [9.5, 10.5, 10.5, 12.5, 13.5], ["A"] * 5, 2
    )
    assert signals == ["NO_SIGNAL", "NO_SIGNAL", "NO_SIGNAL", "BULLISH_BREAKOUT", "NO_SIGNAL"]


def test_rsi_wilder_edge_cases_and_seed():
    from rovexquant.indicators.momentum import rsi

    rising = rsi([float(value) for value in range(1, 17)], period=14)
    falling = rsi([float(value) for value in range(16, 0, -1)], period=14)
    flat = rsi([10.0] * 16, period=14)
    assert rising[:14] == [None] * 14
    assert rising[14:] == [100.0, 100.0]
    assert falling[14:] == [0.0, 0.0]
    assert flat[14:] == [50.0, 50.0]


def test_atr_uses_previous_close_for_gap_and_wilder_smoothing():
    from rovexquant.indicators.volatility import atr

    values = atr(
        highs=[11.0, 15.0, 14.0],
        lows=[9.0, 13.0, 12.0],
        closes=[10.0, 14.0, 13.0],
        period=2,
    )
    assert values[:2] == [None, None]
    assert values[2] == pytest.approx(3.5)


def test_macd_histogram_is_line_minus_signal_and_warmup_is_null():
    line, signal, histogram = macd([float(value) for value in range(1, 100)])
    assert line[:25] == [None] * 25
    assert signal[:33] == [None] * 33
    for index, (macd_value, signal_value, histogram_value) in enumerate(zip(line, signal, histogram, strict=True)):
        if macd_value is not None and signal_value is not None:
            assert histogram_value == pytest.approx(macd_value - signal_value)
        else:
            assert histogram_value is None
