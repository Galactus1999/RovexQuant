# RovexQuant — master phase roadmap and acceptance status

**Reviewed:** 25 November 2026  
**Repository:** `Galactus1999/RovexQuant`  
**Rule:** source implementation is not phase acceptance. A phase closes only when its required tests, provider/data evidence, persistence behaviour and relevant Android workflow have been verified on the exact head.

## Phase map

| Phase | Scope | Current status | Exit condition |
|---|---|---|---|
| P1 | Engineering foundations, security, architecture, persistence, observability and CI | **Open** | Close every applicable item in `PHASE1_ACCEPTANCE.md`; no skipped required job counted as pass |
| P2 | Instrument identity, OHLCV charting, indicators, index/sector research panels and CSV research import | **In progress — PR #32 draft** | Official provider route, complete index/sector coverage, durable import workflow, exact-head CI and real provider-response verification |
| P3 | Historical archive backfill and independent exchange reconciliation | **Source present; operationally unverified** | Idempotent backfill run, derived adjustment import, reconciliation permission, persisted report and data-health acceptance |
| P4 | Macro data adapters and PostgreSQL persistence | **Source present; reliability repair in PR #33** | Enabled-source failures fail CI/workflow; verified source freshness and persisted observations |
| P5 | Event archive and issuer/sector evidence | **Source present; source coverage partial** | Scheduled persistence, source freshness, independent-source coverage and honest unavailable-source states |
| P6 | Point-in-time constituents, identity continuity and adjusted research | **Source present; corpus acceptance pending** | Populated PIT corpus, corporate-action consistency, no-look-ahead/survivorship tests and reproducible outputs |
| P7 | Data-health APIs and Android research integration | **Source present; end-to-end acceptance pending** | Deployed backend data, authenticated Android load/error/stale states and exact-device or emulator regression |
| P8 | Backtesting and paper-research engine v2 | **Next development phase** | Deterministic event-driven portfolio simulator, realistic execution assumptions, equity curve/risk metrics, walk-forward and out-of-sample validation |
| P9 | Intraday streaming and session-aware candle construction | **Existing prototype; live-data validation pending** | Authorised provider, reconnect/replay, session boundaries, latency/quality telemetry and stale-data safety |
| P10 | Additional official data adapters | **Planned** | Provider-neutral adapters with documented APIs, terms and contract tests; no order endpoints |
| P11 | Evidence-grounded AI research assistant | **Planned** | Read-only tool boundary, source citations, reproducibility, explicit model configuration and deterministic fallback |
| P12 | Production hardening and release | **Planned** | Security/architecture closeout, backups/restores, observability, release signing, deployment and rollback evidence |

## Evidence snapshot

### P1 — foundations

P1 remains open. Historical PRs #8 and #9 are closed unmerged; do not treat those branches as active integration targets. The current CI has a Phase 1 required-acceptance gate. PR #33 run #2150 passed that gate on commit `daf34232e9262c2b59b8915d6edf7c3079406121`, but the dependency-lock persistence and production PostgreSQL migration jobs were skipped. The written P1 checklist still has open architecture, structured logging/metrics, safe-error, backup/restore and key-rotation requirements.

### P2 — charting and index/sector research

PR #32 remains draft and unmerged. Commit `0bc241a54814784648815e37c34f4fcd28cda955` passed CI run #2141, including 16 Android 15 emulator tests. Subsequent commits replaced the daily index/sector route that depended on an undocumented NSE web endpoint with Upstox V3 historical daily candles, added canonical index-name resolution and added index-specific panel context. The first CI attempt on the provider migration found missing enum references and one path-encoding test issue. Those were repaired; the latest exact-head CI must pass before this batch is accepted. No live index/sector response has yet been independently verified from the user's configured Upstox token.

The existing panel now derives daily OHLC statistics (52-week high/low/range position, annualised realised volatility, maximum drawdown, average daily range and period returns) from validated daily bars. Constituent weights, breadth, contributions and rebalance dates remain explicitly unavailable until a validated point-in-time constituent feed exists.

Automatic folder-based CSV import is still pending. The current manual single-file import is not equivalent to a persisted folder watch/import workflow.

### P3 — historical archive and reconciliation

The source and migrations exist in `main`, but recent workflow history contains no verified production backfill/reconciliation run. The workflow is `workflow_dispatch` only, not scheduled. Official exchange reconciliation is permission-gated; the TejHQ public Parquet archive remains provisional until rows are independently reconciled.

### P4 — macro persistence

The 24 November scheduled run showed World Bank and AMFI adapters succeeding (283 and 100 observations/rows seen, with zero new upserts because the rows were already present). FRED was skipped because no API key was configured. All three RBIH mirror requests returned HTTP 404. PR #33 disables that experimental mirror by default and makes failures from enabled providers fail the workflow. PR #33 run #2150 passed all executed CI jobs and the Phase 1 gate; dependency-lock persistence and production PostgreSQL migration jobs were skipped. A new scheduled production refresh after this repair has not yet been run.

### P5 — event archive

The 25 November scheduled archive run persisted eight official RBI articles/events. GDELT was unavailable in that run; SEBI and PIB returned no articles. The archive is useful but its source coverage is not complete or uniform.

### P6/P7 — point-in-time research and Android integration

The backend modules, migrations, tests and Android research screens are present in `main`. Production acceptance is still blocked on a populated/reconciled historical corpus, deployed database verification and end-to-end Android validation against actual persisted macro/event/market data. Unit tests alone do not prove these workflows.

## Execution order and gates

1. **Close the current P2 repair batch:** exact-head CI, Android emulator regression, official Upstox V3 daily index/sector request tests, provider-master alias tests and a live response check after the operator configures a read-only Upstox Analytics Token. Keep PR #32 isolated until accepted.
2. **Finish the P2 CSV workflow:** implement user-selected SAF folder access with persisted URI permission, bounded scanning, hash-based deduplication, instrument/timeframe mapping, atomic import, revocation/error handling and regression tests. Do not crawl all device storage.
3. **Execute P3 historical backfill in a controlled environment:** verify source hashes, import checkpoints, row counts and idempotent rerun. Run official exchange reconciliation only with the required permission reference. Do not invent a schedule.
4. **Complete P4/P5 provider health:** retain explicit skipped/unavailable states, verify freshness and ensure any enabled provider failure is visible.
5. **Validate P6/P7 against populated data:** reconcile corporate actions and point-in-time membership, then test authenticated Android loading, stale/error states and source provenance.
6. **Start P8 on deterministic fixtures in parallel, but do not promote strategies or claim research validity until P3/P6 gates pass.** Existing backtesting is a simple long-only simulator; P8 expands it to portfolio-aware event-driven research with execution costs, gap/stop handling, equity curves, risk metrics and walk-forward/out-of-sample evaluation. No order execution.
7. Only then progress P9–P12.

## Global acceptance rules

- No invented provider data, timestamps, constituent weights, breadth or event attribution.
- Distinguish EOD, delayed, intraday and live data. A successful workflow is not proof of fresh market data.
- Do not count skipped CI jobs or optional provider omissions as successful tests.
- Do not merge draft PRs or modify production credentials/signing configuration without explicit authorization.
- Every code batch must be audited and tested on its exact commit. Record build, emulator, live-provider, database and physical-device evidence separately.
