from dataclasses import dataclass
from datetime import datetime
from math import isfinite
from typing import Sequence

@dataclass(frozen=True, slots=True)
class BacktestBar:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float

@dataclass(frozen=True, slots=True)
class BacktestConfig:
    initial_capital: float = 100_000.0
    risk_fraction: float = 0.01
    max_position_fraction: float = 0.25
    stop_fraction: float = 0.02
    commission_bps: float = 5.0
    slippage_bps: float = 5.0

    def __post_init__(self) -> None:
        values = (self.initial_capital, self.risk_fraction, self.max_position_fraction, self.stop_fraction, self.commission_bps, self.slippage_bps)
        if not all(isfinite(value) for value in values):
            raise ValueError("backtest parameters must be finite")
        if self.initial_capital <= 0 or not 0 < self.risk_fraction <= 0.05:
            raise ValueError("capital must be positive and risk_fraction in (0, 5%]")
        if not 0 < self.max_position_fraction <= 1 or not 0 < self.stop_fraction < 1:
            raise ValueError("allocation must be in (0, 1] and stop_fraction in (0, 1)")
        if self.commission_bps < 0 or self.slippage_bps < 0:
            raise ValueError("cost assumptions cannot be negative")

@dataclass(frozen=True, slots=True)
class BacktestTrade:
    entry_time: datetime
    exit_time: datetime
    entry_price: float
    exit_price: float
    quantity: int
    net_pnl: float
    return_fraction: float
    exit_reason: str

@dataclass(frozen=True, slots=True)
class BacktestResult:
    initial_capital: float
    final_equity: float
    total_return_fraction: float
    max_drawdown_fraction: float
    win_rate: float
    profit_factor: float | None
    trade_count: int
    trades: tuple[BacktestTrade, ...]

def run_long_only_backtest(
    bars: Sequence[BacktestBar],
    signals: Sequence[int],
    config: BacktestConfig = BacktestConfig(),
) -> BacktestResult:
    """Signals are close-confirmed events: +1 enter, -1 exit, 0 hold.
    Orders fill at the next bar open. Stops are checked on later bars only.
    """
    if len(bars) != len(signals):
        raise ValueError("bars and signals must have equal lengths")
    if any(signal not in (-1, 0, 1) for signal in signals):
        raise ValueError("signals must be -1, 0 or 1")
    if len(bars) < 2:
        raise ValueError("at least two bars are required")
    previous_time = None
    for bar in bars:
        if bar.timestamp.tzinfo is None or bar.timestamp.utcoffset() is None:
            raise ValueError("bar timestamps must be timezone-aware")
        if previous_time is not None and bar.timestamp <= previous_time:
            raise ValueError("bars must be strictly chronological")
        if not all(isfinite(value) and value > 0 for value in (bar.open, bar.high, bar.low, bar.close)):
            raise ValueError("OHLC values must be finite and positive")
        if bar.low > min(bar.open, bar.close) or bar.high < max(bar.open, bar.close) or bar.low > bar.high:
            raise ValueError("invalid OHLC range")
        previous_time = bar.timestamp

    cash = config.initial_capital
    quantity = 0
    entry_price = 0.0
    entry_fee = 0.0
    entry_notional = 0.0
    stop_price = 0.0
    entry_time = bars[0].timestamp
    trades: list[BacktestTrade] = []
    equity_curve: list[float] = [cash]
    commission = config.commission_bps / 10_000.0
    slippage = config.slippage_bps / 10_000.0

    def close_position(raw_price: float, timestamp: datetime, reason: str) -> None:
        nonlocal cash, quantity, entry_price, entry_fee, entry_notional, stop_price
        fill = raw_price * (1.0 - slippage)
        exit_fee = fill * quantity * commission
        cash += fill * quantity - exit_fee
        pnl = (fill - entry_price) * quantity - entry_fee - exit_fee
        trades.append(BacktestTrade(
            entry_time=entry_time,
            exit_time=timestamp,
            entry_price=entry_price,
            exit_price=fill,
            quantity=quantity,
            net_pnl=pnl,
            return_fraction=(pnl / entry_notional) if entry_notional else 0.0,
            exit_reason=reason,
        ))
        quantity = 0
        entry_price = entry_fee = entry_notional = stop_price = 0.0

    for index in range(1, len(bars)):
        bar = bars[index]
        stopped = False
        if quantity > 0 and bar.low <= stop_price:
            stop_reference = min(bar.open, stop_price)
            close_position(stop_reference, bar.timestamp, "STOP")
            stopped = True

        signal = signals[index - 1]
        if quantity > 0 and signal == -1:
            close_position(bar.open, bar.timestamp, "SIGNAL")
        elif quantity == 0 and not stopped and signal == 1 and (index == 1 or signals[index - 2] != 1):
            fill = bar.open * (1.0 + slippage)
            stop_distance = fill * config.stop_fraction
            equity_before_entry = cash
            by_risk = int((equity_before_entry * config.risk_fraction) / stop_distance)
            by_allocation = int((equity_before_entry * config.max_position_fraction) / fill)
            by_cash = int(equity_before_entry / (fill * (1.0 + commission)))
            quantity = max(0, min(by_risk, by_allocation, by_cash))
            if quantity > 0:
                entry_price = fill
                entry_time = bar.timestamp
                entry_notional = fill * quantity
                entry_fee = entry_notional * commission
                stop_price = fill - stop_distance
                cash -= entry_notional + entry_fee

        equity_curve.append(cash + quantity * bar.close)

    if quantity > 0:
        final_bar = bars[-1]
        close_position(final_bar.close, final_bar.timestamp, "END_OF_TEST")
        equity_curve[-1] = cash

    peak = config.initial_capital
    max_drawdown = 0.0
    for equity in equity_curve:
        peak = max(peak, equity)
        if peak > 0:
            max_drawdown = max(max_drawdown, (peak - equity) / peak)

    wins = [trade.net_pnl for trade in trades if trade.net_pnl > 0]
    losses = [-trade.net_pnl for trade in trades if trade.net_pnl < 0]
    gross_wins, gross_losses = sum(wins), sum(losses)
    profit_factor = gross_wins / gross_losses if gross_losses else (None if not gross_wins else None)
    final_equity = cash
    return BacktestResult(
        initial_capital=config.initial_capital,
        final_equity=final_equity,
        total_return_fraction=(final_equity / config.initial_capital) - 1.0,
        max_drawdown_fraction=max_drawdown,
        win_rate=(len(wins) / len(trades)) if trades else 0.0,
        profit_factor=profit_factor,
        trade_count=len(trades),
        trades=tuple(trades),
    )
