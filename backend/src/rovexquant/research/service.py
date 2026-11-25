"""Application services for deterministic indicator and backtest requests.

This layer owns orchestration of deterministic engines. It has no FastAPI or
transport dependency; routes only validate, authorize, call these functions,
and map domain validation failures to HTTP responses.
"""
from __future__ import annotations

from typing import Any

from rovexquant.backtesting.engine import BacktestBar, BacktestConfig, run_long_only_backtest
from rovexquant.indicators.momentum import rsi
from rovexquant.indicators.moving_average import ema, sma
from rovexquant.indicators.trend import adx, macd
from rovexquant.indicators.volatility import atr, bollinger_bands
from rovexquant.research.contracts import BacktestRequest, IndicatorRequest


def calculate_indicators(request: IndicatorRequest) -> dict[str, Any]:
    bb_upper, bb_middle, bb_lower = bollinger_bands(
        request.close, request.bollinger_period, request.bollinger_deviations
    )
    result: dict[str, Any] = {
        "sma": sma(request.close, request.sma_period),
        "ema": ema(request.close, request.ema_period),
        "rsi": rsi(request.close, request.rsi_period),
        "macd": macd(request.close),
        "bollinger_bands": {
            "upper": bb_upper, "middle": bb_middle, "lower": bb_lower,
        },
    }
    if request.high is not None or request.low is not None:
        if request.high is None or request.low is None:
            raise ValueError("high and low must be supplied together")
        if not (len(request.high) == len(request.low) == len(request.close)):
            raise ValueError("OHLC arrays must have equal lengths")
        result["atr"] = atr(request.high, request.low, request.close, request.atr_period)
        result["adx"] = adx(request.high, request.low, request.close, request.adx_period)
    return result


def run_backtest(request: BacktestRequest) -> dict[str, Any]:
    if len(request.bars) != len(request.signals):
        raise ValueError("bars and signals must have equal lengths")
    config = BacktestConfig(
        initial_capital=request.initial_capital,
        risk_fraction=request.risk_fraction,
        max_position_fraction=request.max_position_fraction,
        stop_fraction=request.stop_fraction,
        commission_bps=request.commission_bps,
        slippage_bps=request.slippage_bps,
        periods_per_year=request.periods_per_year,
    )
    bars = [BacktestBar(**bar.model_dump()) for bar in request.bars]
    result = run_long_only_backtest(bars, request.signals, config)
    return {
        "initial_capital": result.initial_capital,
        "final_equity": result.final_equity,
        "total_return_fraction": result.total_return_fraction,
        "max_drawdown_fraction": result.max_drawdown_fraction,
        "win_rate": result.win_rate,
        "profit_factor": result.profit_factor,
        "trade_count": result.trade_count,
        "gross_profit": result.gross_profit,
        "gross_loss": result.gross_loss,
        "expectancy": result.expectancy,
        "average_win": result.average_win,
        "average_loss": result.average_loss,
        "max_consecutive_losses": result.max_consecutive_losses,
        "exposure_fraction": result.exposure_fraction,
        "cagr_fraction": result.cagr_fraction,
        "annualized_volatility_fraction": result.annualized_volatility_fraction,
        "sharpe_ratio": result.sharpe_ratio,
        "calmar_ratio": result.calmar_ratio,
        "equity_curve": [{
            "timestamp": point.timestamp,
            "cash": point.cash,
            "position_market_value": point.position_market_value,
            "equity": point.equity,
            "position_quantity": point.position_quantity,
        } for point in result.equity_curve],
        "trades": [{
            "entry_time": trade.entry_time,
            "exit_time": trade.exit_time,
            "entry_price": trade.entry_price,
            "exit_price": trade.exit_price,
            "quantity": trade.quantity,
            "net_pnl": trade.net_pnl,
            "return_fraction": trade.return_fraction,
            "exit_reason": trade.exit_reason,
        } for trade in result.trades],
    }
