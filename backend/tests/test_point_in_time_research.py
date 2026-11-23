from __future__ import annotations

from datetime import date, datetime, timezone

import pytest

from rovexquant.market_data import eod_store


class _Cursor:
    def __init__(self, rows):
        self.rows = rows

    def fetchall(self):
        return self.rows


class _Connection:
    def __init__(self, responses):
        self.responses = list(responses)
        self.queries = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, query, params=()):
        self.queries.append((query, params))
        return _Cursor(self.responses.pop(0))


def test_point_in_time_symbol_history_resolves_isin_and_returns_adjusted_ohlc(monkeypatch):
    connection = _Connection([
        [("INE002A01018",)],
        [(
            date(2026, 1, 2), "RELIANCE", "INE002A01018",
            110.0, 121.0, 99.0, 115.0, 108.0, 1000, 115000.0,
            "tejhq_hf_parquet", "MATCHED", datetime(2026, 1, 3, tzinfo=timezone.utc),
            100, date(2000, 1, 1), None, "tejhq_hf_parquet",
            "CORPORATE_ACTION_ADJUSTED", 1.1,
        )],
    ])
    monkeypatch.setattr(eod_store, "connect", lambda: connection)
    bars = eod_store.point_in_time_symbol_history(
        "NSE", "RELIANCE", from_date=date(2026, 1, 1), to_date=date(2026, 1, 31)
    )
    assert bars[0]["isin"] == "INE002A01018"
    assert bars[0]["open"] == 110.0
    assert bars[0]["close"] == 115.0
    assert bars[0]["symbol_history_provider"] == "tejhq_hf_parquet"
    assert bars[0]["price_basis"] == "CORPORATE_ACTION_ADJUSTED"
    assert "market_symbol_history" in connection.queries[1][0]
    assert "adjusted.adjustment_factor * bar.open" in connection.queries[1][0]


def test_point_in_time_symbol_history_rejects_reused_ticker_ambiguity(monkeypatch):
    connection = _Connection([[("INE002A01018",), ("INE009A01021",)]])
    monkeypatch.setattr(eod_store, "connect", lambda: connection)
    with pytest.raises(eod_store.EodStoreError, match="multiple ISINs") as error:
        eod_store.point_in_time_symbol_history(
            "NSE", "ABC", from_date=date(2020, 1, 1), to_date=date(2026, 1, 1)
        )
    assert error.value.status_code == 409


def test_point_in_time_universe_reports_membership_and_bar_coverage(monkeypatch):
    connection = _Connection([[
        ("INE002A01018", "RELIANCE", date(1995, 1, 1), None, "tejhq_hf_parquet",
         "EQ", 1200.0, 100000, "MATCHED", 2),
        ("INE467B01029", "TCS", date(2004, 1, 1), None, "tejhq_hf_parquet",
         None, None, None, None, 2),
    ]])
    monkeypatch.setattr(eod_store, "connect", lambda: connection)
    result = eod_store.point_in_time_universe("NSE", as_of=date(2026, 1, 2))
    assert result["total_members"] == 2
    assert result["members_with_bars"] == 1
    assert result["coverage_fraction"] == 0.5
    assert result["point_in_time_coverage"] is False
    assert result["members"][0]["symbol"] == "RELIANCE"


def test_point_in_time_universe_performance_invalidates_index_on_low_coverage(monkeypatch):
    connection = _Connection([[
        (date(2026, 1, 2), 100, 95, 0.01),
        (date(2026, 1, 5), 100, 60, 0.02),
        (date(2026, 1, 6), 100, 98, -0.01),
    ]])
    monkeypatch.setattr(eod_store, "connect", lambda: connection)
    result = eod_store.point_in_time_universe_performance(
        "NSE", from_date=date(2026, 1, 1), to_date=date(2026, 1, 31), minimum_coverage=0.8
    )
    assert result["status"] == "LOW_COVERAGE"
    assert result["days"][0]["cumulative_index"] == pytest.approx(101.0)
    assert result["days"][1]["index_valid"] is False
    assert result["days"][1]["cumulative_index"] is None
    assert result["days"][2]["cumulative_index"] is None


def test_point_in_time_universe_performance_bounds_calendar_range():
    with pytest.raises(eod_store.EodStoreError):
        eod_store.point_in_time_universe_performance(
            "NSE", from_date=date(2024, 1, 1), to_date=date(2026, 1, 1)
        )
