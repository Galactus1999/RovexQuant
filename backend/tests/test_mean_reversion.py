import pytest
from rovexquant.indicators.volatility import bollinger_bands
from rovexquant.strategies.mean_reversion import BollingerReentry

def test_bollinger_bands_have_rolling_warmup():
    upper, middle, lower = bollinger_bands([10, 10, 10, 5, 9], period=3, deviations=1)
    assert middle[:2] == [None, None]
    assert middle[2] == pytest.approx(10.0)
    assert upper[2] == pytest.approx(10.0)
    assert lower[2] == pytest.approx(10.0)

def test_bollinger_reentry_is_confirmed_on_close():
    strategy = BollingerReentry(period=3, deviations=1)
    signals = strategy.evaluate([10, 10, 10, 5, 9])
    assert signals[-1] == "BULLISH_REENTRY"

def test_bollinger_rejects_non_finite_prices():
    with pytest.raises(ValueError):
        bollinger_bands([10, float("nan"), 11], period=2)
