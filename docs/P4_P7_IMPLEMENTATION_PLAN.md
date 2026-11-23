# P4–P7 implementation plan and acceptance

This plan extends the P1 market-ingestion reliability work and initial connector
catalogue. Provider adapters remain isolated; no provider is authoritative for
every data family.

## P4 — macroeconomic data
- [x] World Bank Indicators API v2 adapter (keyless; allowlisted series).
- [x] Optional FRED adapter (server-side FRED_API_KEY; fail-soft when absent).
- [ ] RBI DBIE official file importer with source-file hashing and publication metadata.
- [ ] AMFI NAV import, kept separate from equity-price instruments.
- Acceptance: annual/monthly frequencies and observation periods remain intact;
  no forward-fill into daily bars; every observation carries source code and URL.

## P5 — news, geopolitical and corporate events
- [x] GDELT DOC 2.0 discovery already powers the Intelligence Centre.
- [x] Cache identical searches for five minutes and serialize outbound calls to
  a conservative greater-than-five-second minimum interval; surface HTTP 429 as
  a recoverable provider condition.
- [x] Return retrieved-at and latest GDELT-seen timestamps with explicit semantics.
- [ ] Scheduled durable event ingestion and event deduplication in PostgreSQL.
- [ ] Corroborate material events against RBI, SEBI, PIB and issuer/exchange filings.
- [ ] Add listed-company/ISIN entity resolution with confidence and review queue.
- Acceptance: retain original publisher URL; distinguish indexed time from
  publication time; no uncorroborated headline becomes a stock recommendation.

## P6 — quantitative research
- [x] Deterministic EOD event-window study from persisted PostgreSQL bars.
- [x] Pre-event baseline and post-event return, volatility and drawdown metrics.
- [ ] Corporate-action-adjusted return chain and survivorship-bias-free universe.
- [ ] Benchmark-relative abnormal returns and sector/industry benchmark mapping.
- [ ] Walk-forward validation, transaction costs, liquidity and delisting controls.
- Acceptance: point-in-time universe and data vintage are recorded; no look-ahead
  joins; event study does not imply causation when no benchmark is supplied.

## P7 — application integration and provenance
- [x] Authenticated macro endpoints for World Bank and FRED.
- [x] Authenticated EOD event-study endpoint.
- [x] Existing Intelligence Centre displays GDELT source, retrieval time, source
  domains, GDELT-seen time, heuristic classification score and sector hypothesis.
- [ ] Add macro series and event-study views to Android Research/Intelligence UI
  through QuantDataRepository and typed models (never direct UI networking).
- [ ] Show provider status, source age, frequency, data mode and stale/error state
  consistently on Pulse, sector, macro and event cards.
- Acceptance: Android consumes only authenticated normalized APIs; each card
  explains provider, observation period, retrieval time, frequency and delay.

## Integration priorities (execution order)
1. TejHQ EOD + PostgreSQL durability (P1): stabilize daily snapshot and status.
2. Hugging Face partitioned Parquet backfill (P2): ingest bulk yearly partitions
   by date batches, not one HTTP request per symbol.
3. Independent reconciliation (P3): compare selected exchange/date/symbol rows
   with official bhavcopy or jugaad-data; quarantine mismatches rather than
   silently overwrite primary data.
4. World Bank + FRED + RBI + AMFI macro/fund series (P4).
5. GDELT + official filings and event-to-issuer evidence (P5).
6. DuckDB/Polars research, event studies and point-in-time backtesting (P6).
7. Provenance-rich mobile views and diagnostics (P7).

## Current scope boundary
P4 World Bank/FRED adapters, P5 GDELT cache/rate limiting and provenance,
and P6 deterministic EOD event-window calculations are implemented in the
current branch. P7 backend routes/provenance are implemented; Android macro
and event-study screens remain outstanding. P2/P3 are prioritized but not
claimed complete. Production data availability still depends on applying
migrations, provider connectivity, and configured optional credentials.
