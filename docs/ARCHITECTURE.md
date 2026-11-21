# Architecture

Independent signals/research/paper-trading app. No order placement.

## Modes
- Local zero-budget: Android client, local FastAPI, SQLite and permitted historical/public datasets.
- Hosted later: authenticated API, persistent worker and PostgreSQL. Free tiers are not assumed always-on.
- Broker-connected later: optional Zerodha Kite Connect and DhanHQ market-data adapters, implemented only after current official API, entitlement, rate-limit and data-use terms are verified.

## Modules
`market_data`, `instruments`, `indicators`, `strategies`, `risk`, `signals`, `backtesting`, `ai`, `api`. Each owns one domain responsibility. Strategy logic depends on internal contracts, never broker SDK types.

## Data safety
Persist provider, exchange timestamp, receive timestamp, completeness and quality. Stale, incomplete, gap-detected, disconnected or adjustment-pending data suppresses signals. Never label delayed data live.

## Bias controls
Point-in-time fundamentals and classifications; delisted securities retained; corporate actions adjusted; no future data; realistic spread, slippage, fees, taxes, partial fills, liquidity and circuits.

## AI boundary
AI receives computed evidence and may explain it. It cannot create prices, override risk gates, set quantity or place orders. Secrets never enter source control.
