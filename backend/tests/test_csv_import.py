from datetime import datetime, timezone
from uuid import uuid4
import pytest
from rovexquant.domain.market import DataQuality
from rovexquant.market_data.csv_import import import_ohlcv_csv

def test_imports_timezone_aware_ohlcv_csv():
    content = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00+05:30,100,102,99,101,1000\n"
    bars = import_ohlcv_csv(content, uuid4(), "5m", received_at=datetime(2025, 1, 1, tzinfo=timezone.utc))
    assert len(bars) == 1
    assert bars[0].quality == DataQuality.VALID
    assert bars[0].volume == 1000
    assert bars[0].end_at.minute == 20

def test_rejects_naive_timestamps():
    content = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00,100,102,99,101,1000\n"
    with pytest.raises(ValueError, match="timezone"):
        import_ohlcv_csv(content, uuid4(), "5m")

def test_rejects_unsorted_or_duplicate_rows():
    content = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00Z,100,102,99,101,1000\n2025-01-01T09:15:00Z,100,102,99,101,1000\n"
    with pytest.raises(ValueError, match="increasing"):
        import_ohlcv_csv(content, uuid4(), "1m")

def test_rejects_invalid_ohlc():
    content = "timestamp,open,high,low,close,volume\n2025-01-01T09:15:00Z,100,98,99,101,1000\n"
    with pytest.raises(ValueError, match="OHLC"):
        import_ohlcv_csv(content, uuid4(), "1m")
