# RovexQuant Branch Consolidation Ground

**Prepared:** 2026-11-25  
**Repository:** `Galactus1999/RovexQuant`  
**Authoritative base at preparation:** `main` @ `80a619c91a8daf4c775c9940dbaeda1f64d1a030`  
**Preparation branch:** `integration/branch-consolidation-ground`

## Purpose and safety rules

This branch is the staging ground for consolidation. It is not a replacement for `main`, and it does not authorize blind branch merges.

1. Preserve the current mainline as the authoritative baseline.
2. Classify every branch by its unique commits and file-level changes against the same mainline SHA.
3. Merge only after the branch's unique work is understood, rebased/ported, and tested against the staging ground.
4. Where a branch is substantially behind or diverged, port selected features rather than merging its historical tree.
5. Do not delete or force-update source branches during consolidation.
6. Preserve research-only operation: no broker order placement, no autonomous trading, and no AI override of deterministic risk controls.
7. Treat market data, provenance, timestamps, instrument identity, and point-in-time validity as data-integrity requirements.
8. Record CI as passed only when the exact candidate SHA has a completed successful workflow.

## Live repository snapshot

- `main`: `80a619c91a8daf4c775c9940dbaeda1f64d1a030`.
- P2 PR #36: `integration/p2-rebased-final` @ `9667d3ef9d642cbe9764754e8f147fb679b17408`; 164 commits ahead, 0 behind, 49 changed files, mergeable/clean at inspection.
- Exact-head P2 CI run #2209 / run ID `45649755601`: completed with conclusion `success`. The workflow had required gates passing; two conditional jobs were skipped in the earlier job-level inspection.
- PR #32 (`feat/p2-indicator-integration`) and PR #27 (`integration/quant-ai-research-history-mainline`) are older competing proposals. Their base SHAs are stale; do not merge them as-is.
- `integration/p2-rebased` is identical to current `main` and contributes no unique commits.
- Current branch list contained 49 branches, including this preparation branch.

## Branch inventory and initial disposition

Ahead/behind counts are relative to the `main` snapshot above. Counts are commit graph facts, not quality assessments.

| Branch | Ahead | Behind | Initial disposition |
|---|---:|---:|---|
| `copilot/grounded-ai-responsive-intelligence` | 0 | 54 | Historical/behind; inspect for unique unmerged content only |
| `feat/ai-integrations-studio` | 14 | 192 | Diverged AI UI/API; compare with larger AI branches, port selectively |
| `feat/ai-intelligence-research-workspace` | 0 | 302 | Behind; no unique commits against main |
| `feat/ai-intelligence-tool-registry` | 139 | 189 | Major diverged AI/tool-registry candidate; inspect and port selectively |
| `feat/ai-research-history-ledger` | 198 | 189 | Major diverged AI history/archive candidate; inspect migrations and APIs |
| `feat/frontend-visual-first` | 179 | 24 | P2/UI lineage; compare with PR #36 and avoid duplicate merge |
| `feat/p0-bounded-market-stream` | 140 | 24 | Diverged market-stream candidate; compare protocol and tests |
| `feat/p2-indicator-integration` | 161 | 24 | Older P2 candidate; superseded by PR #36 pending consolidation |
| `feat/p2-p3-historical-reconciliation` | 0 | 242 | Behind; no unique commits against main |
| `feat/p4-macro-data-persistence` | 0 | 238 | Behind; no unique commits against main |
| `feat/p5-event-archive` | 0 | 235 | Behind; no unique commits against main |
| `feat/p6-point-in-time-research` | 0 | 228 | Behind; no unique commits against main |
| `feat/p7-data-health-acceptance` | 0 | 199 | Behind; no unique commits against main |
| `feat/p8-backtesting-analytics` | 0 | 55 | Behind; no unique commits against main |
| `feat/premium-quant-terminal-ui` | 3 | 909 | Very old UI divergence; inspect only for isolated useful design work |
| `feat/quant-ai-android-integration` | 0 | 474 | Behind; no unique commits against main |
| `feat/quant-ai-intelligence-foundation` | 0 | 830 | Behind; no unique commits against main |
| `feat/quant-autonomous-market-ingestion` | 0 | 353 | Behind; no unique commits against main |
| `feat/quant-engine-quality-ui-expansion` | 86 | 909 | Very old divergent prototype; do not merge tree |
| `feat/quant-eod-market-pulse` | 0 | 368 | Behind; no unique commits against main |
| `feat/quant-market-data-provider-core` | 0 | 877 | Behind; no unique commits against main |
| `feat/quant-market-data-provider-core-ci` | 0 | 875 | Behind; no unique commits against main |
| `feat/quant-neon-postgres-vault` | 0 | 757 | Behind; no unique commits against main |
| `feat/quant-operational-market-data-ui` | 0 | 331 | Behind; no unique commits against main |
| `feat/quant-phase1-engineering-foundations` | 2 | 545 | Diverged build-tooling/docs changes; inspect exact diff |
| `feat/quant-secure-provider-onboarding` | 0 | 401 | Behind; no unique commits against main |
| `feat/quant-tejhq-eod-research` | 0 | 769 | Behind; no unique commits against main |
| `feat/quant-upstox-oauth-streaming` | 0 | 845 | Behind; no unique commits against main |
| `feat/research-data-connectors-launcher` | 40 | 312 | Diverged connector/ingestion work; inspect for unique provider code |
| `feature/p2-charting-foundation` | 0 | 22 | Behind; no unique commits against main |
| `feature/p2-indicator-integration` | 22 | 27 | Older P2 indicator implementation; compare with PR #36 |
| `feature/pulse-interactive-research` | 34 | 60 | Diverged interactive research; compare with PR #36 and P1 identity |
| `fix/android-emulator-research-navigation-test` | 1 | 59 | One unique test commit; port only if not already covered |
| `fix/p1-auth-token-hardening` | 0 | 53 | Behind; auth hardening appears in newer mainline ancestry |
| `fix/p1-instrument-identity` | 28 | 60 | Diverged identity/TejHQ integration; compare with P2 implementation |
| `fix/p1-market-data-flow` | 86 | 312 | Diverged ingestion/macro/event research; selective port candidate |
| `fix/p4-macro-refresh-health` | 0 | 54 | Behind; no unique commits against main |
| `fix/quant-android-apk-ci-regression` | 0 | 880 | Behind; no unique commits against main |
| `fix/quant-auth-independent-ui-regressions` | 0 | 542 | Behind; no unique commits against main |
| `fix/quant-phase1-nse-mcp-live-smoke` | 20 | 441 | Diverged NSE MCP smoke/test improvements; inspect and port selectively |
| `fix/quant-upstox-v3-binary-subscription` | 0 | 441 | Behind; no unique commits against main |
| `integration/ai-studio-mainline` | 26 | 192 | Diverged AI Studio; superseded/overlapped by larger AI history branch |
| `integration/ai-studio-mainline-v2` | 14 | 187 | Diverged AI Studio subset; compare, do not merge independently |
| `integration/branch-consolidation-ground` | 0 | 0 | This staging branch; preparation only |
| `integration/p2-rebased` | 0 | 0 | Identical to main; no unique work |
| `integration/p2-rebased-final` | 164 | 0 | PR #36; validated P2 candidate, merge in stage 2 |
| `integration/quant-ai-research-history-mainline` | 84 | 187 | Diverged AI/archive/NSE-MCP candidate; port selectively |
| `integration/quant-ai-research-mainline` | 44 | 187 | Older AI subset; compare against research-history branch |
| `main` | 0 | 0 | Authoritative baseline |
| `snapshot/ai-merge-base` | 1 | 187 | Temporary snapshot workflow only; exclude from product merge |
| `snapshot/p2-merge-base` | 1 | 24 | Temporary snapshot workflow only; exclude from product merge |

## Preparation findings and compatibility notes

### P2 charting / instrument identity

The P2 PR is the best current integration candidate because it is rebased directly on current main, has no behind commits, resolves the previously reported UI/navigation conflicts, and has exact-head CI success.

The P2 diff removes `NiftyIndicesOhlcvClient.kt`. This is not automatically a defect: the P2 instrument identity registry routes registered NSE broad-market and sector indices to `UPSTOX_V3_HISTORICAL` / `UPSTOX_V3_INTRADAY`; the backend candle adapter resolves index instruments through the validated NSE index master and Upstox V3. This replacement must be retained only if the merged end-to-end tests confirm index history and intraday requests. Do not reintroduce the website-backed Nifty Indices scraper merely because it existed on main.

Index breadth, constituent weights, and constituent contribution remain unavailable until a validated point-in-time constituent/breadth feed exists. Do not derive these fields from index OHLC candles. CSV folder import is a user-selected Storage Access Framework scan of immediate children, not a background watcher.

### AI research/history branches

The largest useful unique AI branch family contains:
- backend claim verification (`claim_verifier.py`) and tests;
- durable AI research archive (`research_archive.py`), migration `0008_ai_research_runs.py`, and migration tests;
- AI gateway and AI router changes;
- AI mobile API models/repository/UI panels;
- NSE MCP research improvements and provider metadata.

These are candidates for selective porting. Before port, reconcile migration head/order with the current Alembic graph, inspect all API request/response contracts against Android clients, check auth/rate limits/provider secret handling, and verify the claim-verifier output is evidence-grounded. Do not merge the old branch's full `quant-ci.yml`, dependency/toolchain, or UI tree over newer main.

### Market ingestion / macro / research branches

`fix/p1-market-data-flow` has unique ingestion, macro, event-study, official-feed, EOD worker, and related test changes but is 312 commits behind. Port individual modules only after checking current equivalents, migrations, lifecycle ownership, scheduler/worker idempotency, and API schemas. It is not safe for a wholesale merge.

`fix/quant-phase1-nse-mcp-live-smoke` has a small unique diff focused on the NSE MCP live smoke script, service and tests; validate external connectivity as a separate live check, not as a substitute for deterministic tests.

### Stream / protocol branches

Compare `feat/p0-bounded-market-stream` with the current backend/client protocol before porting. The backend Upstox upstream subscription frame is binary UTF-8 JSON; the Android-to-Rovex backend control message is a separate private text-frame protocol. Preserve that boundary.

## Staged integration order

### Stage 1 — ground and port adjustments (this branch)

- Establish this branch from the exact current main SHA.
- Keep this branch's inventory and decision log current.
- Port compatibility changes and isolated unique modules only after file-level review.
- Do not merge source branches wholesale.
- Add tests for every ported cross-module contract.
- Keep a list of unresolved risks and exact validation evidence.

### Stage 2 — branch integration

1. Merge PR #36 (P2) after confirming its head SHA and CI are unchanged.
2. Integrate selected P1 identity/stream work only where not already included by P2/main.
3. Port AI research/history/tool registry in small batches, including migration and Android/backend API contracts.
4. Port ingestion/macro/event and NSE MCP improvements in dependency order.
5. Account for every remaining branch as merged/equivalent, selectively ported, obsolete/behind, or snapshot-only. Do not delete branches during this pass.

### Final audit and repair

- Android: compile, lint, unit tests, emulator navigation, chart/index/sector workflows, CSV import, and API parsing.
- Backend: full unit/regression suite, lint/type/static checks, migration upgrade/downgrade/head consistency, auth and API contract tests.
- Integration: instrument identity/provider routing, quote/candle/indicator contract, streaming frame types, retry/cancellation/timeout behavior, CSV persistence/idempotency, and UI loading/error/empty states.
- Data integrity: source attribution, exchange timezone, timestamps, OHLC validation, corporate-action caveats, index volume/breadth limitations, and point-in-time correctness.
- Security: no client-side provider secrets, strict auth boundaries, safe URL/provider allowlists, bounded payloads, no arbitrary forwarded-IP trust.
- Operational: worker idempotency, lifecycle/shutdown, database connectivity/migrations, deployment env documentation, and failure observability.
- Run the complete exact-head CI workflow after repairs. A skipped conditional job is not a pass; report it as skipped.
- Final output must identify the exact main SHA, exact workflow run, tests executed, skipped checks, unresolved limitations, and source archive checksum.

## Non-negotiable acceptance criteria

- No blind merges of branches with hundreds of commits behind main.
- No silent loss of current mainline features.
- No fake market data or fabricated provider availability.
- No AI-owned market-data truth, execution, or risk decisions.
- No autonomous trading or broker order placement.
- No claims of a successful build, live feed, migration, deployment, or device test without corresponding evidence.


## Preparation work now present on this branch

The following work has been ported onto the staging ground after file-level review:

1. **P2 charting / instrument identity / CSV import** — PR #36 was merged into this staging branch as merge commit `067b1829ef1ec756042f89852a5bdb9804b42e36`. It has not been merged into `main`.
2. **AI research archive foundation** — added migration `0008_ai_research_runs`, bounded research-run persistence, source-reference storage, and claim-verifier primitives from the AI research-history branch.
3. **AI task routing** — added validated server-owned `QUANT_AI_TASK_ROUTES` routing for supported task types; explicit provider/model pairs remain supported for existing callers.
4. **Authenticated AI archive APIs** — added owner-session-protected budget, run-list, run-detail, and task-route endpoints.
5. **Evidence-bounded strategy verification** — strategy briefs now attach a deterministic EOD source reference, call an independently configured verifier when available, and persist the research run. If no independent route or archive is configured, the core EOD result remains available and the verification/archive status is reported rather than fabricated.
6. **Bounded market-stream processing** — ported the small unique P0 stream change set: bounded frame ingress, latest-value dashboard coalescing, optional bounded chart tick flow, dropped-frame telemetry, and regression tests. The Android-to-backend subscription remains a text JSON control frame; the backend-to-Upstox subscription protocol remains a separate binary UTF-8 JSON frame.

These changes are preparation work only. They have not yet been proven by a new full CI run on the combined staging SHA. The next step is to run exact-head CI and repair any failures before promoting this ground to `main`.

## Updated staging comparisons

After P2 was merged into the ground, the old P2 branch `feat/p2-indicator-integration` is now behind and has no unique commits against this staging branch. The P0 bounded-stream branch has only four commits / three files not already in the ground; those three files have now been ported. The other old P2 branches remain comparison-only unless a later file-level review identifies a genuinely unique correction.

The AI research-history branch remains far behind and diverged (84 commits ahead of this ground but 425 behind it at the latest comparison). Its whole tree remains excluded. Since the earlier inventory, the current ground now also has Android repository/API/UI wiring for persistent AI run history and daily/monthly token-budget telemetry, plus the DalalOS research panel and the existing AI research/briefing panels. This does not mean the entire PR #27 feature set is integrated: 14 PR-only paths remain outside PR #38, including the optional official-bhavcopy adapter and permission tests, LiteLLM pricing data, build-toolchain scripts and workflow changes. Review those individually; do not merge PR #27 wholesale. PR #32 has no unique commits relative to this ground and is superseded by the P2.3 work already present.
