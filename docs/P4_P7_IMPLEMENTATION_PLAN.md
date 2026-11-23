# P4–P7 implementation plan and acceptance

This plan extends the P1 market-ingestion reliability work and initial connector
catalogue. Provider adapters remain isolated; no provider is authoritative for
every data family.

## P4 — macroeconomic data
- [x] World Bank Indicators API v2 adapter (keyless; allowlisted series).
- [x] Optional FRED adapter (server-side FRED_API_KEY; fail-soft when absent).
- [ ] RBI DBIE time-series importer. Official DBIE exposes downloadable Excel tables; a stable documented public API was not verified. Current implementation exposes RBI official announcements through RSS, not DBIE numeric series.
- [ ] AMFI NAV import, kept separate from equity-price instruments.
- Acceptance: annual/monthly frequencies and observation periods remain intact;
  no forward-fill into daily bars; every observation carries source code and URL.

## P5 — news, geopolitical and corporate events
- [x] GDELT DOC 2.0 discovery already powers the Intelligence Centre.
- [x] Official RBI, SEBI and PIB RSS adapters with publisher-domain allowlisting, timestamp normalization, bounded payloads and ten-minute cache; merged with GDELT headlines in Intelligence Centre.
- [x] Cache identical searches for five minutes and serialize outbound calls to
  a conservative greater-than-five-second minimum interval; surface HTTP 429 as
  a recoverable provider condition.
- [x] Return retrieved-at and latest GDELT-seen timestamps with explicit semantics.
- [ ] Scheduled durable event ingestion and historical event archive in PostgreSQL. Current official/GDELT feed cache is process-local and on-demand.
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
- [x] Authenticated EOD event-study endpoint with optional benchmark-relative market-adjusted return and cumulative abnormal-return calculations on matched dates.
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

## Implementation status — source complete, production validation gated

The P4–P7 implementation is present in this branch and has been integrated with the latest `main` Pulse/EOD worker changes. The phase labels below describe source implementation, not a claim that a deployed service has already collected production data.

- **P4 — macro persistence:** World Bank, optional FRED, AMFI NAV and the allowlisted delayed RBIH DBIE mirror adapters; versioned PostgreSQL observations, deduplication/content hashes, authenticated refresh/history/status routes and a scheduled/manual refresh workflow.
- **P5 — event archive:** GDELT discovery plus RBI/SEBI/PIB source feeds; durable event/source/run persistence, normalized deduplication, scheduled/manual refresh, archive/status routes and conservative exact-name/symbol issuer candidates. Sector effects remain hypotheses, not verified causal links.
- **P6 — point-in-time research:** ISIN-based identity resolution and historical ticker continuity, corporate-action-adjusted OHLC consistency, point-in-time constituent reconstruction and equal-weighted historical universe performance with coverage invalidation. Research output is descriptive and not a tradable portfolio.
- **P7 — data health and Android:** authenticated health/reconciliation APIs and Android research views for macro, events and event studies, with provenance/freshness context and deterministic CSV research utilities.

### Remaining release and production gates

1. Complete the current GitHub Actions Android compile, lint and APK-integrity jobs on the merged phase branch; resolve any failures before calling the branch green.
2. Merge only after the PR is mergeable and all required checks pass against the latest `main`.
3. Apply the database migration in the deployed environment and verify the actual migration revision.
4. Configure the existing database connection secret for the scheduled macro refresh; configure `FRED_API_KEY` only if FRED coverage is required.
5. Verify scheduled GitHub Actions execution, persisted rows, source timestamps, archive freshness and health/reconciliation outputs against the deployed backend.
6. Verify Android login, refresh, loading, stale-data, empty-data, provider-error and backend-unavailable states against the deployed service.

A green GitHub build proves source/build/test correctness only. It does not prove production credentials, external-provider availability, scheduler execution or live data collection. AMFI NAV is fund NAV, not an equity quote; the RBIH DBIE mirror is delayed and must be checked against RBI's portal; GDELT is discovery rather than primary-source verification. NSE MCP and other providers remain subject to their own usage terms.

P1–P3 (TejHQ EOD durability, bulk historical backfill and independent reconciliation) remain separate integration priorities and are not silently marked complete by P4–P7.
