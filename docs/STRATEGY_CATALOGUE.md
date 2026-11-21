# Strategy catalogue

Strategies are isolated, versioned research hypotheses—not guaranteed edges or investment recommendations. No strategy may emit a trade signal from stale, incomplete, unauthorised or unadjusted data.

## Implemented research prototypes
- Trend: SMA crossover, EMA alignment.
- Momentum: RSI regime context (not a standalone entry).
- Breakout: prior-window Donchian breakout; relative-volume-confirmed Donchian breakout; Bollinger re-entry.
- Intraday: session-reset HLC3 VWAP cross; opening-range breakout after configured opening bars.
- Indicators: SMA, EMA, RSI, MACD, ATR, ADX, session VWAP, OBV, prior-window relative volume and Bollinger Bands.

## Planned research candidates
- Trend: Supertrend, Ichimoku, multi-timeframe ADX.
- Momentum: stochastic, ROC and sector-relative strength.
- Breakout: volatility contraction, gap continuation and volume-by-time-of-day.
- Mean reversion: Bollinger re-entry and range-regime filters.
- Portfolio: point-in-time quality, value, momentum and low-volatility factors; sector rotation.

## Validation rules
- Signals use completed bars; backtest entries occur no earlier than the next bar open.
- Donchian channel calculations exclude the current bar.
- Opening-range calculations exclude the breakout bar from the opening range.
- Session VWAP resets at each supplied exchange session key.
- Costs include configurable commission and slippage; stop gaps are filled at the worse of stop or opening price before slippage.
- Walk-forward and out-of-sample testing, corporate-action adjustment, survivorship/look-ahead checks and liquidity/circuit handling remain required before promotion.

Report expectancy, profit factor, maximum drawdown, turnover, exposure, trade count and tail losses—not win rate alone. The current simulator is long-only and does not model market impact, partial fills, queue position, taxes/levies by instrument, short borrow or derivatives margin.
