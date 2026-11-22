from datetime import date, datetime, timezone
import pytest
from rovexquant.market_data.eod_store import EodStoreError, normalize_snapshot_rows


def _row(symbol="RELIANCE", series="EQ", **changes):
    row = {
        "symbol": symbol, "series": series, "isin": "INE002A01018", "trades": 100, "name": "Reliance Industries",
        "open": 100, "high": 110, "low": 95, "close": 105,
        "prev_close": 102, "volume": 1000, "turnover": 105000,
    }
    row.update(changes)
    return row


def test_normalizes_nse_eq_with_explicit_eod_provenance():
    retrieved = datetime(2026, 11, 22, 12, tzinfo=timezone.utc)
    rows = normalize_snapshot_rows(
        [_row()], exchange="nse", trading_date=date(2026, 11, 20),
        provider="tejhq_eod", retrieved_at=retrieved,
    )
    assert rows[0]["exchange"] == "NSE"
    assert rows[0]["symbol"] == "RELIANCE"
    assert rows[0]["close"] == 105
    assert rows[0]["quality_status"] == "PROVISIONAL"
    assert rows[0]["isin"] == "INE002A01018"
    assert rows[0]["trades"] == 100
    assert rows[0]["source_retrieved_at"] == retrieved


def test_filters_exchange_specific_equity_series():
    nse = normalize_snapshot_rows(
        [_row(series="BE"), _row("HDFCBANK"), _row("INFY", "BZ")], exchange="NSE",
        trading_date=date(2026, 11, 20), provider="tejhq_eod",
    )
    bse = normalize_snapshot_rows(
        [_row("RELIANCE", "A"), _row("TCS", "B"), _row("INFY", "T"),
         _row("NOPE", "Z")],
        exchange="BSE", trading_date=date(2026, 11, 20), provider="tejhq_eod",
    )
    assert [item["symbol"] for item in nse] == ["RELIANCE", "HDFCBANK", "INFY"]
    assert [item["series"] for item in bse] == ["A", "B", "T"]


@pytest.mark.parametrize("changes", [
    {"open": 0}, {"high": 90}, {"low": 111}, {"volume": -1},
    {"volume": 1.5}, {"close": float("nan")},
])
def test_rejects_invalid_rows(changes):
    with pytest.raises(EodStoreError):
        normalize_snapshot_rows(
            [_row(**changes)], exchange="NSE",
            trading_date=date(2026, 11, 20), provider="tejhq_eod",
        )


def test_rejects_duplicate_symbol_series():
    with pytest.raises(EodStoreError, match="duplicate"):
        normalize_snapshot_rows(
            [_row(), _row()], exchange="NSE",
            trading_date=date(2026, 11, 20), provider="tejhq_eod",
        )


def test_rejects_naive_retrieval_timestamp():
    with pytest.raises(EodStoreError, match="timezone-aware"):
        normalize_snapshot_rows(
            [_row()], exchange="NSE", trading_date=date(2026, 11, 20),
            provider="tejhq_eod", retrieved_at=datetime(2026, 11, 20),
        )
