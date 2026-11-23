# RovexQuant autonomous market-data ingestion

Status: implementation branch. Production collection is not verified until
database secrets are configured, migrations are applied, and a scheduled/manual
workflow completes against the live TejHQ service.

## Architecture

- Provider adapters fetch data; they do not own persistence or analytics.
- eod_store.py validates, normalizes and persists source-labelled records.
- collect_eod_snapshot.py is a one-shot scheduled/manual EOD collector.
- Authenticated app requests atomically create a run plus a PostgreSQL queue row.
- eod_worker.py claims jobs with FOR UPDATE SKIP LOCKED, renewable leases,
  bounded retry/backoff, and recovery after process restarts. Multiple API instances
  may run workers safely against the same queue.
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
- QUANT_TOKEN_MIGRATION_DATABASE_URL: direct, non-pooler URL for the token/runtime database.
- QUANT_MARKET_MIGRATION_DATABASE_URL: optional direct URL when QUANT_MARKET_DATABASE_URL points to a separate PostgreSQL database. The API fails startup if a distinct market database is not at the expected Alembic revision.

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

## Production deployment and schema ordering

The API validates the exact Alembic schema head at startup, including the
leased ingestion queue migration (0003_market_ingestion_queue). Therefore the new
database migration must complete before a new backend image receives traffic.
Do not rely on the scheduled EOD collector to perform the first migration.

The Quant CI workflow now has a production-only migration job. On a push to
main, it waits for the Phase 1 acceptance gate, requires the GitHub Actions
secret QUANT_TOKEN_MIGRATION_DATABASE_URL (direct, non-pooled PostgreSQL URL
with TLS), applies Alembic migrations, and reports the resulting revision.
It does not run against pull-request branches or expose production credentials
to PR workflows.

Render's current documentation states that pre-deploy commands are available
for paid web services, private services and background workers, not Free web
services. For a Free Render deployment, configure Auto-Deploy to "After CI
Checks Pass" and require the Quant Production PostgreSQL Migration check before
allowing the new service deploy. The existing application DATABASE URL should
remain the pooled runtime URL; the migration secret must use the direct endpoint.
If the migration secret is absent or migration fails, the deployment gate must
remain red and the previous healthy deployment should continue serving.

References:
- Render deploy ordering and CI integration: https://render.com/docs/deploys
- Render Docker and pre-deploy command support: https://render.com/docs/docker

## Next gates

1. Apply migration to the configured PostgreSQL database.
2. Run the workflow manually and verify NSE/BSE source dates and row counts.
3. Verify the leased worker with process-kill/restart and multi-worker contention tests.
4. Connect Android source-health UI to the authenticated status/history routes.
5. Add bounded historical Parquet backfill after verifying dataset schema and
   terms; do not use Git history as the archive.
6. Add point-in-time sector-index history before calculating sector rotation.
7. Add independent reconciliation and mark records verified only after actual
   cross-source agreement.
8. Add company/event evidence ingestion as a separate provenance-aware pipeline.

## References

- TejHQ API: https://tejhq.dev/docs
- TejHQ data terms: https://tejhq.dev/data-terms
- TejHQ pipeline: https://github.com/tejhq/tej-bazaar
- yfinance documentation: https://ranaroussi.github.io/yfinance/
- Yahoo personal-use disclaimer: https://github.com/ranaroussi/yfinance
- Upstox V3 historical candles: https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/
- GitHub schedule event: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows


## Android persisted-data integration (Phase 2)

The authenticated endpoint
`GET /api/v1/app/market-data/ingestion/snapshot/{exchange}?limit=20`
builds Market Pulse breadth and movers from the latest successful PostgreSQL
batch. It does not call TejHQ at request time and does not silently substitute
unpersisted provider data. If no successful batch exists, the API returns an
explicit unavailable response.

The Android Market Pulse now consumes this persisted snapshot and separately
reads `GET /api/v1/app/market-data/ingestion/status?exchange=nse`. The status
contract exposes the latest run and the most recent successful run independently,
so a failed later collection does not hide the last successful trading date.
The UI displays provider, trading date, row count, run state and bounded error
detail. Live Upstox ticks remain a separate stream and are never described as
persisted EOD history.

The scheduled workflow now fails when database or direct migration credentials
are absent. This is intentional: a green no-op would conceal the fact that no
market data was collected.
