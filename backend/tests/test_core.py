from decimal import Decimal
import pytest
from rovexquant.indicators.moving_average import sma, ema
from rovexquant.indicators.momentum import rsi
from rovexquant.risk.position_sizing import RiskProfile, position_quantity
from rovexquant.strategies.registry import default_registry

def test_sma():
    assert sma([1, 2, 3, 4], 3) == [None, None, 2.0, 3.0]

def test_ema_seed():
    assert ema([1, 2, 3, 4], 3)[2] == pytest.approx(2.0)

def test_rsi_flat():
    assert rsi([10.0] * 20, 14)[-1] == 50.0

def test_registry_has_distinct_horizon_strategies():
    strategies = default_registry().all()
    ids = [item.metadata.strategy_id for item in strategies]
    horizons = {item.metadata.horizon for item in strategies}
    assert len(ids) == len(set(ids))
    assert len(ids) >= 6
    assert {"intraday", "swing"} <= horizons

def test_position_size():
    profile = RiskProfile(Decimal("100000"), Decimal("0.01"))
    assert position_quantity(profile, Decimal("100"), Decimal("95")) == 200

def test_position_size_rejects_wrong_side_stop():
    profile = RiskProfile(Decimal("100000"), Decimal("0.01"))
    assert position_quantity(profile, Decimal("100"), Decimal("105")) == 0
    assert position_quantity(profile, Decimal("100"), Decimal("105"), side="short") == 200

def test_risk_cap():
    with pytest.raises(ValueError):
        RiskProfile(Decimal("100000"), Decimal("0.10"))
