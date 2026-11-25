from datetime import datetime, timedelta, timezone
import pytest
from rovexquant.backtesting.engine import BacktestBar, BacktestConfig, run_long_only_backtest

def bars(prices):
    start = datetime(2025, 1, 1, tzinfo=timezone.utc)
    return [BacktestBar(start + timedelta(days=i), p, p + 2, p - 1, p) for i, p in enumerate(prices)]

def test_signal_fills_at_next_open_and_closes_on_next_open():
    data = bars([100, 100, 105, 110])
    result = run_long_only_backtest(data, [1, 0, -1, 0], BacktestConfig(stop_fraction=0.2, commission_bps=0, slippage_bps=0))
    assert result.trade_count == 1
    assert result.trades[0].entry_time == data[1].timestamp
    assert result.trades[0].exit_time == data[3].timestamp
    assert result.final_equity == pytest.approx(100_500.0)

def test_costs_reduce_backtest_return():
    data = bars([100, 100, 110, 110])
    free = run_long_only_backtest(data, [1, 0, -1, 0], BacktestConfig(stop_fraction=0.2, commission_bps=0, slippage_bps=0))
    costly = run_long_only_backtest(data, [1, 0, -1, 0], BacktestConfig(stop_fraction=0.2, commission_bps=10, slippage_bps=10))
    assert costly.final_equity < free.final_equity

def test_stop_is_applied_on_subsequent_bar():
    data = bars([100, 100, 95, 96])
    result = run_long_only_backtest(data, [1, 0, 0, 0], BacktestConfig(stop_fraction=0.02, commission_bps=0, slippage_bps=0))
    assert result.trade_count == 1
    assert result.trades[0].exit_reason == "STOP"

def test_rejects_non_chronological_bars():
    data = bars([100, 101, 102])
    with pytest.raises(ValueError):
        run_long_only_backtest([data[1], data[0], data[2]], [0, 0, 0])

def test_rejects_future_leakage_signal_values():
    with pytest.raises(ValueError):
        run_long_only_backtest(bars([100, 101]), [2, 0])

def test_stop_can_trigger_after_entry_on_same_bar():
    data = bars([100, 100, 101])
    entry_bar = data[1]
    from dataclasses import replace
    data[1] = replace(entry_bar, low=97)
    result = run_long_only_backtest(data, [1, 0, 0], BacktestConfig(stop_fraction=0.02, commission_bps=0, slippage_bps=0))
    assert result.trade_count == 1
    assert result.trades[0].exit_reason == "STOP"
    assert result.trades[0].entry_time == result.trades[0].exit_time


def test_backtest_returns_timestamped_equity_curve_and_trade_analytics():
    data = bars([100, 100, 105, 110])
    result = run_long_only_backtest(
        data,
        [1, 0, -1, 0],
        BacktestConfig(stop_fraction=0.2, commission_bps=0, slippage_bps=0),
    )
    assert len(result.equity_curve) == len(data)
    assert result.equity_curve[0].equity == pytest.approx(100_000.0)
    assert result.equity_curve[-1].equity == pytest.approx(result.final_equity)
    assert result.equity_curve[-1].timestamp == data[-1].timestamp
    assert result.gross_profit == pytest.approx(500.0)
    assert result.gross_loss == 0.0
    assert result.expectancy == pytest.approx(500.0)
    assert result.average_win == pytest.approx(500.0)
    assert result.average_loss is None
    assert result.exposure_fraction == pytest.approx(1.0)
    assert result.max_consecutive_losses == 0


def test_annualised_risk_metrics_require_explicit_bar_frequency():
    data = bars([100, 100, 105, 110])
    implicit = run_long_only_backtest(
        data, [1, 0, -1, 0],
        BacktestConfig(stop_fraction=0.2, commission_bps=0, slippage_bps=0),
    )
    explicit = run_long_only_backtest(
        data, [1, 0, -1, 0],
        BacktestConfig(stop_fraction=0.2, commission_bps=0, slippage_bps=0, periods_per_year=252),
    )
    assert implicit.annualized_volatility_fraction is None
    assert implicit.sharpe_ratio is None
    assert explicit.annualized_volatility_fraction is not None
    assert explicit.sharpe_ratio is not None


def test_max_consecutive_losses_counts_sequential_losing_trades():
    data = bars([100, 100, 90, 90, 80, 80, 70])
    result = run_long_only_backtest(
        data, [1, 0, -1, 1, 0, -1, 0],
        BacktestConfig(stop_fraction=0.5, commission_bps=0, slippage_bps=0),
    )
    assert result.trade_count == 2
    assert result.max_consecutive_losses == 2
    assert result.gross_loss > 0
    assert result.expectancy is not None and result.expectancy < 0


def test_backtest_config_rejects_invalid_periods_per_year():
    with pytest.raises(ValueError, match="periods_per_year"):
        BacktestConfig(periods_per_year=0)


def test_research_service_exposes_equity_curve_and_analytics():
    from rovexquant.research.contracts import BacktestRequest
    from rovexquant.research.service import run_backtest

    request = BacktestRequest(
        bars=[
            {"timestamp": f"2025-01-0{day}T15:30:00+05:30", "open": price,
             "high": price + 2, "low": price - 1, "close": price}
            for day, price in enumerate([100, 100, 105, 110], start=1)
        ],
        signals=[1, 0, -1, 0],
        stop_fraction=0.2,
        commission_bps=0,
        slippage_bps=0,
        periods_per_year=252,
    )
    result = run_backtest(request)
    assert len(result["equity_curve"]) == 4
    assert result["equity_curve"][0]["equity"] == pytest.approx(100_000.0)
    assert result["expectancy"] == pytest.approx(500.0)
    assert result["annualized_volatility_fraction"] is not None
    assert result["sharpe_ratio"] is not None
