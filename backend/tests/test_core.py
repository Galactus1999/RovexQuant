from decimal import Decimal
import pytest
from rovexquant.indicators.moving_average import sma, ema
from rovexquant.indicators.momentum import rsi
from rovexquant.risk.position_sizing import RiskProfile, position_quantity
from rovexquant.strategies.registry import default_registry

def test_sma(): assert sma([1,2,3,4], 3) == [None,None,2.0,3.0]
def test_ema_seed(): assert ema([1,2,3,4], 3)[2] == pytest.approx(2.0)
def test_rsi_flat(): assert rsi([10.0]*20, 14)[-1] == 50.0
def test_registry_unique():
    ids = [s.metadata.strategy_id for s in default_registry().all()]
    assert len(ids) == len(set(ids)) and len(ids) >= 3
def test_position_size():
    p = RiskProfile(Decimal("100000"), Decimal("0.01"))
    assert position_quantity(p, Decimal("100"), Decimal("95")) == 200
def test_risk_cap():
    with pytest.raises(ValueError): RiskProfile(Decimal("100000"), Decimal("0.10"))
