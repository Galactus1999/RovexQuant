from datetime import datetime, timedelta, timezone
import pytest
from rovexquant.backtesting.engine import BacktestBar, BacktestConfig, run_long_only_backtest

def bars(prices):
    start = datetime(2025, 1, 1, tzinfo=timezone.utc)
    return [BacktestBar(start + timedelta(days=i), p, p + 2, p - 2, p) for i, p in enumerate(prices)]

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
