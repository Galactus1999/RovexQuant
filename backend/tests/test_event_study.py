from datetime import date

import pytest

from rovexquant.research.event_study import calculate_event_window


def _bars():
    return [
        {"date": "2026-01-01", "close": 100.0},
        {"date": "2026-01-02", "close": 102.0},
        {"date": "2026-01-05", "close": 101.0},
        {"date": "2026-01-06", "close": 105.0},
        {"date": "2026-01-07", "close": 103.0},
    ]


def test_event_study_uses_trading_bars_and_reports_non_causal_metrics():
    result = calculate_event_window(
        _bars(), event_date=date(2026, 1, 5), pre_bars=2, post_bars=2
    )
    assert result["anchor_date"] == "2026-01-02"
    assert result["first_post_event_date"] == "2026-01-05"
    assert result["last_post_event_date"] == "2026-01-06"
    assert result["pre_event_bars_used"] == 2
    assert result["post_event_bars_used"] == 2
    assert result["market_adjusted_cumulative_return"] is None
    assert result["cumulative_abnormal_return"] is None
    assert result["causal_inference"] is False


def test_event_study_rejects_missing_pre_or_post_observations():
    with pytest.raises(ValueError):
        calculate_event_window(
            [{"date": "2026-01-05", "close": 100.0}],
            event_date=date(2026, 1, 5),
        )


def test_event_study_bounds_requested_windows():
    with pytest.raises(ValueError):
        calculate_event_window(_bars(), event_date=date(2026, 1, 5), post_bars=100)


def test_event_study_computes_benchmark_relative_returns_on_matching_dates():
    asset = [
        {"date": "2026-01-01", "close": 100.0},
        {"date": "2026-01-02", "close": 102.0},
        {"date": "2026-01-05", "close": 105.0},
        {"date": "2026-01-06", "close": 108.0},
    ]
    benchmark = [
        {"date": "2026-01-01", "close": 200.0},
        {"date": "2026-01-02", "close": 204.0},
        {"date": "2026-01-05", "close": 206.0},
        {"date": "2026-01-06", "close": 210.0},
    ]
    result = calculate_event_window(
        asset, event_date=date(2026, 1, 5), post_bars=2,
        benchmark_bars=benchmark, benchmark_symbol="NIFTY 50",
    )
    assert result["benchmark_symbol"] == "NIFTY 50"
    assert result["benchmark_observations_matched"] == 2
    assert result["market_adjusted_cumulative_return"] is not None
    assert result["cumulative_abnormal_return"] is not None
    assert result["causal_inference"] is False


def test_event_study_rejects_duplicate_asset_dates():
    with pytest.raises(ValueError):
        calculate_event_window(
            [
                {"date": "2026-01-01", "close": 100.0},
                {"date": "2026-01-01", "close": 101.0},
                {"date": "2026-01-05", "close": 102.0},
            ],
            event_date=date(2026, 1, 5),
        )


def test_adjusted_event_study_uses_corporate_action_basis():
    asset = [{**bar, "price_basis": "CORPORATE_ACTION_ADJUSTED"} for bar in _bars()]
    benchmark = [
        {"date": bar["date"], "close": bar["close"] * 2, "price_basis": "CORPORATE_ACTION_ADJUSTED"}
        for bar in _bars()
    ]
    result = calculate_event_window(
        asset, event_date=date(2026, 1, 5), post_bars=2,
        benchmark_bars=benchmark, benchmark_symbol="NIFTYBEES",
    )
    assert result["price_basis"] == "CORPORATE_ACTION_ADJUSTED"
    assert result["benchmark_price_basis"] == "CORPORATE_ACTION_ADJUSTED"
    assert result["method"] == "CORPORATE_ACTION_ADJUSTED_MARKET_EVENT_WINDOW"
    assert result["benchmark_observations_matched"] == 2


def test_event_study_disables_benchmark_when_price_bases_differ():
    asset = [{**bar, "price_basis": "CORPORATE_ACTION_ADJUSTED"} for bar in _bars()]
    benchmark = [{**bar, "price_basis": "RAW_CLOSE"} for bar in _bars()]
    result = calculate_event_window(
        asset, event_date=date(2026, 1, 5), post_bars=2,
        benchmark_bars=benchmark, benchmark_symbol="NIFTYBEES",
    )
    assert result["market_adjusted_cumulative_return"] is None
    assert result["benchmark_observations_matched"] == 0
    assert result["price_basis"] == "CORPORATE_ACTION_ADJUSTED"


def test_event_study_labels_mixed_adjustment_coverage():
    bars = _bars()
    bars[0]["price_basis"] = "CORPORATE_ACTION_ADJUSTED"
    result = calculate_event_window(bars, event_date=date(2026, 1, 5), post_bars=2)
    assert result["price_basis"] == "MIXED_RAW_AND_ADJUSTED"
    assert result["method"] == "MIXED_PRICE_BASIS_EVENT_WINDOW"


def test_event_study_price_basis_ignores_observations_outside_selected_window():
    asset = _bars()
    asset[-1]["price_basis"] = "CORPORATE_ACTION_ADJUSTED"
    benchmark = [
        {**bar, "close": bar["close"] * 2, "price_basis": "RAW_CLOSE"}
        for bar in _bars()
    ]
    benchmark[-1]["price_basis"] = "CORPORATE_ACTION_ADJUSTED"
    result = calculate_event_window(
        asset, event_date=date(2026, 1, 5), pre_bars=1, post_bars=2,
        benchmark_bars=benchmark, benchmark_symbol="NIFTYBEES",
    )
    assert result["price_basis"] == "RAW_CLOSE"
    assert result["benchmark_price_basis"] == "RAW_CLOSE"
    assert result["benchmark_observations_matched"] == 2
    assert result["market_adjusted_cumulative_return"] is not None
