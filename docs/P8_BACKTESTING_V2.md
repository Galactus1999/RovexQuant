# P8 — Backtesting and paper-research engine v2

## Objective

Upgrade the existing single-instrument, long-only prototype into a reproducible research engine without introducing broker order execution. The first implementation batch adds a timestamped equity curve and risk/trade analytics to the existing deterministic simulator.

## P8.1 — Result integrity and analytics

- Persist one equity point per input bar, including timestamp, cash, marked position value, equity and quantity.
- Report positive and negative trade P&L totals, expectancy, average win/loss, maximum consecutive losses and exposure fraction.
- Report CAGR only when the sample spans at least 30 calendar days.
- Do not infer annualisation frequency from timestamps. Annualised volatility and Sharpe remain null unless the caller explicitly supplies `periods_per_year`.
- Preserve next-open signal fills, adverse stop-gap fills, commission and slippage.
- Keep the current long-only and single-instrument scope explicit.

## P8.2 — Planned portfolio/event engine

- Multi-instrument positions with portfolio-level cash, exposure, per-instrument and sector caps.
- Explicit event ordering: data publication time, signal evaluation, order submission, eligible execution time, fill and portfolio marking.
- No same-bar look-ahead; orders created from a completed bar cannot fill before the next eligible bar.
- Trading calendar/session handling, missing-bar policy, halts/circuit limits and liquidity participation caps.
- Corporate-action cash/quantity adjustments and instrument lifecycle events.
- Benchmark alignment and portfolio attribution.
- Deterministic run manifest: code version, strategy version, configuration, data snapshot/vintage, hashes and provider quality.
- Walk-forward and out-of-sample splits; parameter selection must never use the evaluation window.

## P8.3 — Acceptance tests before strategy promotion

- Next-bar execution, gap-through-stop, same-bar stop after entry, partial/missing bars and market halt.
- Fees, taxes/levies, slippage, liquidity and turnover sensitivity.
- Split/bonus/dividend handling, ticker changes, delisted instruments and point-in-time universe membership.
- No look-ahead in indicators, constituent membership, fundamentals or event timestamps.
- Reproducible output from identical data and configuration hashes.
- Walk-forward and untouched out-of-sample validation with benchmark comparison.

The current batch does not implement multi-instrument execution, partial fills, market impact, taxes/levies, shorting, derivatives margin or paper-order routing. It is a research simulator only, not a trading system.
