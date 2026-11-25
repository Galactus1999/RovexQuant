# RovexQuant P3 — historical archive backfill and independent verification

## P3A: Historical archive backfill

- Source: public TejHQ/indian-markets Hugging Face Parquet dataset.
- NSE archive starts at 2010-01-04; BSE coverage starts at 2024-07-08.
- One exchange/year Parquet object is downloaded and SHA-256 hashed before import.
- DuckDB is installed only in the dedicated GitHub Actions job; it is not added to the Android app or backend runtime dependency graph.
- Rows are streamed in bounded batches, ordered by trading date, validated through the existing EOD normalizer, and upserted into market_eod_bars with provider tejhq_hf_parquet, mode END_OF_DAY, and quality PROVISIONAL.
- Each trading date and its checkpoint commit atomically. Re-runs skip dates already imported from the same source hash. A changed source hash creates a new import generation; database uniqueness prevents duplicate provider bars.
- Historical imports do not overwrite live/stream data and do not automatically become verified.
- The separate backfill_derived operation imports the matching prices_adjusted, actions and symbol_history Parquet trees. Adjusted closes are kept in their own table with source hashes; raw prices remain unchanged.

Manual execution: Actions → Quant Historical Backfill & Exchange Reconciliation → Run workflow. Use operation=backfill for raw bars or operation=backfill_derived for adjusted bars/actions/symbol history; choose NSE/BSE and a year. Import one year per run to bound memory, runtime, and rollback scope.

## P3B: independent exchange reconciliation

- NSE: official UDiFF Common Bhavcopy ZIP from nsearchives.nseindia.com.
- BSE: official UDiFF cash-market bhavcopy CSV from bseindia.com.
- Reconciliation is keyed by exchange, trading date, symbol and series.
- OHLC and previous-close values use a ₹0.01 tolerance; volume and available trade counts must match exactly; ISIN mismatches are recorded separately.
- Missing reference rows, extra stored rows and value/identifier discrepancies are persisted as auditable report rows with the official source URL and SHA-256.
- Only individual rows that match the official file are promoted to VERIFIED_SOURCE; unmatched or missing rows remain provisional.
- Event studies prefer TejHQ back-adjusted close when the adjusted archive is imported. The source methodology adjusts splits, bonuses and dividends, but does not scale demergers, rights, buybacks or mergers. Asset/benchmark comparisons are disabled if their adjustment bases differ.
- Repeated downloads of the same official file are idempotent by (exchange, trading_date, source_sha256).

The current `.github/workflows/quant-market-integrity.yml` workflow is `workflow_dispatch` only. It is operator-triggered; it is NOT scheduled for 19:30/21:30 IST. No automated weekday schedule or retry is active. Historical backfill and derived-data backfill can be dispatched separately. Official exchange reconciliation remains disabled unless `NSE_OFFICIAL_AUTOMATED_DATA_PERMISSION=true` and a non-empty `NSE_OFFICIAL_PERMISSION_REFERENCE` are configured. Do not add an automatic exchange-download schedule without confirming written permission and a holiday-aware operating policy. An official 404 is treated as unavailable for that date (often a holiday) and is not fabricated into a zero-row success.

## Data and use boundaries

This project uses the data for the owner's private, non-commercial research. Exchange-published data and TejHQ's dataset are not automatically covered by the MIT license of TejHQ's pipeline code. Do not redistribute the raw archive, expose bulk downloads to other users, place it in a public release, use it for commercial products, automated order execution, or model training. Confirm current source terms before changing the use case.

## Operational prerequisites

- QUANT_MARKET_DATABASE_URL (or the shared QUANT_TOKEN_DATABASE_URL) must point to the PostgreSQL database that stores market bars and explicitly require TLS.
- QUANT_MARKET_MIGRATION_DATABASE_URL is required for a separate market database; for a shared database use QUANT_TOKEN_MIGRATION_DATABASE_URL.
- The workflow applies reviewed Alembic migrations before data jobs. It does not request, print, or embed credentials.
- Backfill requires the pinned DuckDB operator dependency. It is intentionally isolated from the always-on API runtime.
- The historical import does not invent missing pre-2010 NSE or pre-2024-07-08 BSE history and does not imply intraday/live availability.
