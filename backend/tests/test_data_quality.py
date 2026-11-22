from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import uuid4
from rovexquant.domain.market import DataQuality, MarketBar
from rovexquant.market_data.quality import validate_bar

def make_bar(now, quality=DataQuality.VALID, received_offset=timedelta(0)):
    received = now + received_offset
    return MarketBar(
        instrument_id=uuid4(), timeframe="1m",
        start_at=now - timedelta(minutes=1), end_at=now,
        open=Decimal("100"), high=Decimal("102"), low=Decimal("99"), close=Decimal("101"),
        volume=100, provider="fixture", exchange_timestamp=now,
        received_at=received, is_complete=True, quality=quality,
    )

def test_accepts_fresh_valid_bar():
    now = datetime.now(timezone.utc)
    assert validate_bar(make_bar(now), timedelta(minutes=2), now)[0]

def test_delayed_bar_requires_explicit_permission():
    now = datetime.now(timezone.utc)
    bar = make_bar(now, DataQuality.DELAYED)
    assert validate_bar(bar, timedelta(minutes=2), now)[1] == "delayed_data_not_allowed"
    assert validate_bar(bar, timedelta(minutes=2), now, allow_delayed=True)[0]

def test_rejects_future_received_timestamp():
    now = datetime.now(timezone.utc)
    bar = make_bar(now, received_offset=timedelta(minutes=1))
    assert validate_bar(bar, timedelta(minutes=2), now)[1] == "future_timestamp"

def test_rejects_naive_now():
    now = datetime.now(timezone.utc)
    assert validate_bar(make_bar(now), timedelta(minutes=2), datetime.now())[1] == "now_timezone_missing"
