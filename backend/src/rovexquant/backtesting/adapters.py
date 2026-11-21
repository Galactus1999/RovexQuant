from collections.abc import Sequence
from rovexquant.backtesting.engine import BacktestBar
from rovexquant.domain.market import DataQuality, MarketBar

def to_backtest_bars(bars: Sequence[MarketBar], *, allow_delayed: bool = False) -> list[BacktestBar]:
    """Convert complete, provenance-bearing historical bars; reject unsafe quality states."""
    result: list[BacktestBar] = []
    previous_start = None
    for bar in bars:
        if not bar.is_complete or not bar.valid_ohlc():
            raise ValueError("backtest input contains incomplete or invalid OHLCV")
        if bar.quality != DataQuality.VALID and not (allow_delayed and bar.quality == DataQuality.DELAYED):
            raise ValueError(f"backtest input quality is not permitted: {bar.quality.value}")
        if bar.start_at.tzinfo is None or bar.start_at.utcoffset() is None:
            raise ValueError("backtest timestamps must be timezone-aware")
        if previous_start is not None and bar.start_at <= previous_start:
            raise ValueError("backtest bars must be strictly chronological")
        previous_start = bar.start_at
        result.append(BacktestBar(
            timestamp=bar.start_at,
            open=float(bar.open),
            high=float(bar.high),
            low=float(bar.low),
            close=float(bar.close),
        ))
    if len(result) < 2:
        raise ValueError("at least two market bars are required")
    return result
