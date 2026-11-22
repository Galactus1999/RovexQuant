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


def test_market_database_url_requires_tls_enforcement(monkeypatch):
    from rovexquant.market_data.eod_store import database_url

    monkeypatch.setenv("QUANT_ENV", "production")
    monkeypatch.setenv("QUANT_MARKET_DATABASE_URL", "postgresql://user:pass@db.example/db?sslmode=prefer")
    monkeypatch.delenv("QUANT_TOKEN_DATABASE_URL", raising=False)
    with pytest.raises(EodStoreError, match="require TLS"):
        database_url()

    monkeypatch.setenv("QUANT_MARKET_DATABASE_URL", "postgresql://user:pass@db.example/db?sslmode=require")
    assert database_url().endswith("sslmode=require")


def test_latest_ingestion_status_preserves_last_success_after_newer_failure(monkeypatch):
    from rovexquant.market_data import eod_store

    started = datetime(2026, 11, 23, 4, tzinfo=timezone.utc)
    completed = datetime(2026, 11, 23, 4, 1, tzinfo=timezone.utc)
    last_success = datetime(2026, 11, 21, 4, 30, tzinfo=timezone.utc)
    expected_row = (
        "tejhq_eod", date(2026, 11, 23), "FAILED", 0, 0, 0,
        started, completed, "TejHqError", "provider temporarily unavailable",
        date(2026, 11, 20), "tejhq_eod", 2500, last_success,
    )

    class Result:
        def fetchone(self):
            return expected_row

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def execute(self, query, params):
            assert "LEFT JOIN LATERAL" in query
            assert params == ("NSE", "NSE")
            return Result()

    monkeypatch.setattr(eod_store, "connect", lambda: Connection())
    status = eod_store.latest_ingestion_status("NSE")

    assert status["status"] == "FAILED"
    assert status["trading_date"] == date(2026, 11, 23)
    assert status["last_successful_trading_date"] == date(2026, 11, 20)
    assert status["last_successful_provider"] == "tejhq_eod"
    assert status["last_successful_rows"] == 2500
    assert status["last_successful_at"] == last_success
