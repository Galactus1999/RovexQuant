# P6 — point-in-time identity and survivorship-aware research

## Identity resolution

Event studies now resolve the requested NSE/BSE ticker to a unique ISIN over the requested interval, then follow historical ticker names through the imported TejHQ symbol-history table. Reused tickers that resolve to multiple ISINs are rejected rather than silently mixing companies. Corporate actions are retrieved by ISIN, not just the current ticker.

## Consistent adjusted prices

When a corporate-action adjusted close is present, the same cumulative adjustment factor is applied to OHLC and previous-close fields. Event-study price basis is computed only from the anchor, pre-event and post-event observations actually used. Benchmark-relative returns are disabled if the selected asset and benchmark windows have different or mixed bases.

## Point-in-time universe

- GET /api/v1/research/point-in-time/universe/{exchange}?as_of=YYYY-MM-DD
- GET /api/v1/research/point-in-time/universe-performance/{exchange}?from_date=YYYY-MM-DD&to_date=YYYY-MM-DD

The universe endpoint reconstructs members valid on the requested historical date and reports EOD-bar coverage. The performance endpoint constructs an equal-weighted daily return index from constituents valid on each date, using corporate-action-adjusted prices only. If adjusted-return coverage drops below the configured threshold, the cumulative index is invalidated rather than filled with raw prices.

## Interpretation boundaries

This is a research index, not a tradable portfolio. Results depend on completeness of the historical ISIN/symbol archive and adjusted-price archive. Missing delisted instruments, stale corporate-action data or incomplete source coverage can still create bias. Transaction costs, liquidity, market impact, taxes and execution are not modelled. Event-study output remains descriptive and non-causal.
