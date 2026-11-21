from rovexquant.indicators.volume import on_balance_volume, relative_volume
from rovexquant.strategies.volume_breakout import VolumeConfirmedBreakout

def test_obv_accumulates_by_close_direction():
    assert on_balance_volume([10, 11, 10, 10, 12], [100, 20, 30, 40, 50]) == [0, 20, -10, -10, 40]

def test_relative_volume_uses_prior_bars_only():
    values = relative_volume([10, 10, 10, 30], period=3)
    assert values[:3] == [None, None, None]
    assert values[3] == 3.0

def test_volume_confirmed_breakout_rejects_low_relative_volume():
    strategy = VolumeConfirmedBreakout(channel_period=2, volume_period=2, minimum_relative_volume=1.5)
    signals = strategy.evaluate([10, 11, 12, 13], [8, 9, 10, 11], [9, 10, 11, 12], [100, 100, 100, 100])
    assert signals[-1] == "BREAKOUT_UNCONFIRMED"
