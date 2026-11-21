from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import uuid4
import pytest
from rovexquant.domain.market import DataQuality, MarketBar
from rovexquant.backtesting.adapters import to_backtest_bars

def sample_bar(index, quality=DataQuality.VALID):
    start = datetime(2025, 1, 1, 9, 15, tzinfo=timezone.utc) + timedelta(minutes=index)
    return MarketBar(
        instrument_id=uuid4(), timeframe="1m", start_at=start, end_at=start + timedelta(minutes=1),
        open=Decimal("100"), high=Decimal("102"), low=Decimal("99"), close=Decimal("101"),
        volume=100, provider="fixture", exchange_timestamp=start + timedelta(minutes=1),
        received_at=datetime(2025, 1, 2, tzinfo=timezone.utc), is_complete=True, quality=quality,
    )

def test_converts_complete_valid_bars_in_order():
    result = to_backtest_bars([sample_bar(0), sample_bar(1)])
    assert len(result) == 2
    assert result[0].timestamp.minute == 15

def test_blocks_delayed_data_without_explicit_permission():
    with pytest.raises(ValueError, match="quality"):
        to_backtest_bars([sample_bar(0, DataQuality.DELAYED), sample_bar(1)])

def test_allows_delayed_data_only_when_requested():
    result = to_backtest_bars([sample_bar(0, DataQuality.DELAYED), sample_bar(1)], allow_delayed=True)
    assert len(result) == 2

def test_blocks_incomplete_bar():
    first = sample_bar(0)
    incomplete = MarketBar(**{**{field: getattr(first, field) for field in first.__dataclass_fields__}, "is_complete": False})
    with pytest.raises(ValueError, match="incomplete"):
        to_backtest_bars([incomplete, sample_bar(1)])
