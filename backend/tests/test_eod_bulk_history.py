from datetime import date, datetime, timezone

import pytest

from rovexquant.market_data import eod_store


class _Cursor:
    def __init__(self, rows):
        self.rows = rows

    def fetchall(self):
        return self.rows


class _Connection:
    def __init__(self, rows):
        self.rows = rows
        self.queries = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, query, params=()):
        self.queries.append((query, params))
        return _Cursor(self.rows)


def test_bulk_symbol_history_uses_one_parameterized_query_and_returns_provider_rows(monkeypatch):
    connection = _Connection([(
        "RELIANCE", "EQ", date(2026, 11, 26),
        100.0, 105.0, 99.0, 104.0, 98.0, 1000, 100000.0,
        "tejhq_eod", "PROVISIONAL", datetime(2026, 11, 27, tzinfo=timezone.utc),
        "INE002A01018", 500, "RAW_CLOSE", None,
    )])
    monkeypatch.setattr(eod_store, "connect", lambda: connection)

    rows = eod_store.bulk_symbol_history(
        "NSE",
        [{"symbol": "RELIANCE", "from_date": date(2026, 11, 20)}],
        to_date=date(2026, 11, 27),
        limit_per_symbol=20,
    )

    assert len(rows) == 1
    assert rows[0]["symbol"] == "RELIANCE"
    assert rows[0]["series"] == "EQ"
    assert rows[0]["date"] == "2026-11-26"
    assert rows[0]["provider"] == "tejhq_eod"
    assert rows[0]["data_mode"] == "END_OF_DAY"
    query, params = connection.queries[0]
    assert "unnest(%s::text[], %s::date[])" in query
    assert "ROW_NUMBER() OVER" in query
    assert params[0] == ["RELIANCE"]
    assert params[1] == [date(2026, 11, 20)]
    assert params[-1] == 20


def test_bulk_symbol_history_rejects_duplicate_symbols_before_database_access(monkeypatch):
    monkeypatch.setattr(
        eod_store, "connect",
        lambda: pytest.fail("Database must not be touched for invalid duplicate targets"),
    )
    targets = [
        {"symbol": "RELIANCE", "from_date": date(2026, 11, 20)},
        {"symbol": "RELIANCE", "from_date": date(2026, 11, 21)},
    ]
    with pytest.raises(eod_store.EodStoreError, match="duplicate symbols") as error:
        eod_store.bulk_symbol_history("NSE", targets, to_date=date(2026, 11, 27))
    assert error.value.status_code == 422


def test_bulk_symbol_history_rejects_invalid_exchange_and_target_window():
    with pytest.raises(eod_store.EodStoreError, match="Exchange must be NSE or BSE"):
        eod_store.bulk_symbol_history(
            "NYSE",
            [{"symbol": "RELIANCE", "from_date": date(2026, 11, 20)}],
            to_date=date(2026, 11, 27),
        )
    with pytest.raises(eod_store.EodStoreError, match="date range is invalid"):
        eod_store.bulk_symbol_history(
            "NSE",
            [{"symbol": "RELIANCE", "from_date": date(2026, 11, 28)}],
            to_date=date(2026, 11, 27),
        )
