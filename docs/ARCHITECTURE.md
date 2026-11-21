# Architecture

Independent, provider-agnostic Indian-market research and paper-trading application. No order placement or broker execution endpoints.

## Modes
- Zero-budget local mode: Android client, local FastAPI, local historical CSV import and deterministic research engine.
- Hosted later: authenticated API, persistent worker and PostgreSQL. Free tiers are not assumed always-on.
- Optional broker/data adapters later: Zerodha Kite Connect, DhanHQ or licensed NSE data. Implement only after current official API, entitlement, rate-limit and data-use terms are verified.

## Modules
- market_data, instruments, indicators, strategies, risk, signals, backtesting, ai, api. Strategy code depends on internal contracts, never broker SDK types.

## Data safety
Persist provider, exchange timestamp, receive timestamp, completeness and quality. Stale, incomplete, gap-detected, disconnected or adjustment-pending data suppresses signals. Delayed data requires explicit policy permission and must never be labelled live.

## Research controls
Point-in-time fundamentals and classifications; delisted securities retained; corporate actions adjusted; no future data; realistic spread, slippage, fees, partial fills, liquidity and circuits. Current backtest engine is long-only and does not model market impact, partial fills or exchange-specific taxes/levies; treat results as prototype output.

## AI boundary
AI receives computed evidence and may explain it. It cannot create prices, override risk gates, set quantity or place orders. Secrets never enter source control.
