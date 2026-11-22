# RovexQuant autonomous market-data ingestion

Status: implementation branch. Production collection is not verified until
database secrets are configured, migrations are applied, and a scheduled/manual
workflow completes against the live TejHQ service.

## Architecture

- Provider adapters fetch data; they do not own persistence or analytics.
- eod_store.py validates, normalizes and persists source-labelled records.
- collect_eod_snapshot.py is a one-shot restart-safe EOD batch worker.
- Alembic owns schema changes; the worker never performs DDL.
- PostgreSQL is the durable operational store. GitHub runner disk/cache/artifacts
  are not market-history storage.
- Authenticated backend routes expose ingestion status and persisted history.
- Every row retains exchange, trading date, provider, EOD mode, quality state and
  retrieval timestamp. Retrieval time is never represented as an exchange time.

## TejHQ scheduled collection

TejHQ's documented keyless full-market snapshot is the first collector because
it can retrieve a full NSE/BSE trading-date snapshot without per-symbol loops.
The worker searches a bounded 14-calendar-day window, filters NSE EQ/BE/BZ and BSE
A/B/T series, validates OHLCV, and upserts by exchange/date/symbol/series/provider.
Re-running the same date updates corrected source values instead of duplicating
records.

The current source terms permit an individual's private research and private
dashboards, but prohibit raw-data redistribution and state that commercial or
product use needs a separate plan. Exchange terms independently apply. This
implementation is restricted to the owner's private research installation.

## Yahoo Finance adapter

yahoo_research.py is an explicitly opt-in local personal-research adapter. It is
not wired into the unattended scheduler or app API. Install yfinance with pip only in the
local research environment (pip install yfinance) and set QUANT_ENABLE_YAHOO_PERSONAL_RESEARCH=1.
It supports daily/weekly/monthly history only, sets auto_adjust=False, and keeps
adjusted close, dividends and splits separate from raw OHLCV. It must not be used
as a scheduled production feed or redistributed. The yfinance maintainers state
that the wrapper is unofficial and Yahoo's API is intended for personal use.
Recheck current Yahoo terms before broader use.

## Database setup

Worker environment:
- QUANT_TOKEN_DATABASE_URL or QUANT_MARKET_DATABASE_URL: private PostgreSQL URL
  with TLS enabled (sslmode=require or stronger).
- QUANT_TOKEN_MIGRATION_DATABASE_URL: direct, non-pooler URL for Alembic only.

Apply the schema from backend/:

    alembic -c alembic.ini upgrade head

Run a manual collection:

    python scripts/collect_eod_snapshot.py --exchange both

The worker records a run before fetching and atomically commits the normalized
snapshot plus successful run state. On failure it records a bounded error and
returns non-zero. Re-running is idempotent at the natural source key. Older
dates are retained; missing dates are not silently filled.

## GitHub Actions

quant-eod-ingestion.yml supports manual dispatch and a weekday schedule at
04:30 UTC (10:00 IST). The schedule only runs after this workflow is
merged into the default branch. GitHub schedules may be delayed and public
repository schedules can be disabled after 60 days without activity. Both
database secrets are required. Without them, the workflow explicitly skips
collection; it does not fabricate data or fall back to runner storage.

## Quality semantics

- PROVISIONAL means one documented EOD provider supplied a structurally valid
  row; no independent second-source reconciliation has been performed.
- VERIFIED_SOURCE is reserved for future explicit multi-source reconciliation.
  This implementation never assigns it.
- All persisted records are END_OF_DAY, never LIVE.
- Missing or invalid rows fail the batch rather than creating zero prices.
- NSE MCP is not used by this collector; its separate terms prohibit automated
  strategy ingestion and AI/ML training.
- No automated trading, broker order placement or stock recommendations added.

## Next gates

1. Apply migration to the configured PostgreSQL database.
2. Run the workflow manually and verify NSE/BSE source dates and row counts.
3. Connect Android source-health UI to the authenticated status/history routes.
4. Add bounded historical Parquet backfill after verifying dataset schema and
   terms; do not use Git history as the archive.
5. Add point-in-time sector-index history before calculating sector rotation.
6. Add independent reconciliation and mark records verified only after actual
   cross-source agreement.
7. Add company/event evidence ingestion as a separate provenance-aware pipeline.

## References

- TejHQ API: https://tejhq.dev/docs
- TejHQ data terms: https://tejhq.dev/data-terms
- TejHQ pipeline: https://github.com/tejhq/tej-bazaar
- yfinance documentation: https://ranaroussi.github.io/yfinance/
- Yahoo personal-use disclaimer: https://github.com/ranaroussi/yfinance
- Upstox V3 historical candles: https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/
- GitHub schedule event: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
