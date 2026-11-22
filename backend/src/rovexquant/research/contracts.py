"""Validated HTTP contracts for deterministic research calculations."""
from datetime import datetime

from pydantic import BaseModel, Field


class IndicatorRequest(BaseModel):
    close: list[float] = Field(min_length=1, max_length=10000)
    high: list[float] | None = Field(default=None, max_length=10000)
    low: list[float] | None = Field(default=None, max_length=10000)
    sma_period: int = Field(default=20, ge=1, le=500)
    ema_period: int = Field(default=20, ge=1, le=500)
    rsi_period: int = Field(default=14, ge=2, le=500)
    bollinger_period: int = Field(default=20, ge=2, le=500)
    bollinger_deviations: float = Field(default=2.0, gt=0, le=10)
    atr_period: int = Field(default=14, ge=2, le=500)
    adx_period: int = Field(default=14, ge=2, le=500)


class BacktestBarInput(BaseModel):
    timestamp: datetime
    open: float = Field(gt=0)
    high: float = Field(gt=0)
    low: float = Field(gt=0)
    close: float = Field(gt=0)


class BacktestRequest(BaseModel):
    bars: list[BacktestBarInput] = Field(min_length=2, max_length=10000)
    signals: list[int] = Field(min_length=2, max_length=10000)
    initial_capital: float = Field(default=100000, gt=0)
    risk_fraction: float = Field(default=0.01, gt=0, le=0.05)
    max_position_fraction: float = Field(default=0.25, gt=0, le=1)
    stop_fraction: float = Field(default=0.02, gt=0, lt=1)
    commission_bps: float = Field(default=5, ge=0)
    slippage_bps: float = Field(default=5, ge=0)
