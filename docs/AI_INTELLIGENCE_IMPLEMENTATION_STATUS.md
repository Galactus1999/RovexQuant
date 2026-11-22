# RovexQuant functional and AI intelligence status

**Reviewed:** 2026-11-23  
**Working branch:** `main`  
**Integrated baseline:** `bc6cafe09e2eaf0da7d4b1556696336300836999`  
**Current integration:** PR #18 is merged to `main`; the evidence-contract and NSE MCP hardening changes are being integrated separately.  
**Authoritative product requirement:** [AI Intelligence Centre build prompt](prompts/ROVEXQUANT_AI_INTELLIGENCE_BUILD_PROMPT.md)

This document distinguishes implemented code from features that still depend on credentials, a deployed backend, or unimplemented ingestion/research services. A visible screen is not evidence of a working provider connection.

## Implemented in the current development stack

| Area | Current implementation | Runtime qualification / remaining gap |
|---|---|---|
| Authentication | App session API and Android session handling | Login only establishes the app session. It does not prove market or AI provider connectivity. |
| Market stream | Upstox V3 Protobuf WebSocket bridge; instrument-key validation supports NSE index and NSE cash-equity keys. Android loads official index/equity masters and subscribes to resolved indices plus the equity universe. | Requires server gateway configuration and an owner-provisioned Upstox Analytics Token. Live data is valid only after actual valid ticks are received. No device-level production verification is claimed. |
| EOD market data | TejHQ keyless NSE/BSE OHLCV, snapshot and corporate-action adapter. PR #16 adds a latest-available EOD snapshot API with bounded date lookback and deterministic market breadth/movers. | EOD only, not live. The new endpoint is on-demand after login/manual refresh and does not yet persist a market history or run a scheduled worker. |
| Market Pulse | Live benchmark cards, a 20-index NSE sector watchlist, equity watchlist and an EOD breadth/top movers panel. | Live cards show values only when a valid Upstox tick has actually arrived. Historical sector rotation and constituent breadth are not yet implemented. |
| Technical research | Deterministic SMA, EMA, RSI, MACD, Bollinger Bands, ATR and ADX; deterministic long-only backtest engine; local OHLCV CSV analysis. | Results depend on the quality, completeness and timestamp correctness of supplied OHLCV. The engine is not a validated all-market live scanner. |
| Strategy scanner | Horizon-based playbook UI and local CSV scan summary with trend, Donchian, relative volume and RSI metrics. | The current scanner reads the locally imported dataset. Several playbooks are explicitly prototypes/research candidates; it does not yet scan the full NSE/BSE universe automatically. |
| Signals | Signal lifecycle UI and evidence/risk-gate design. | No populated persistent signal-generation pipeline is currently established; the current screen correctly reports no generated signals. |
| AI provider gateway | Server-side provider-neutral chat completion gateway for supported providers, bounded inputs/outputs and server-side credentials. | A gateway is not itself an autonomous research system. It requires valid provider credentials and a successful live completion request. |
| Android AI workspace | Provider discovery, masked credential entry, chat interface and AI strategy-brief request workflow. | Provider configuration and successful model connectivity are separate states. No configured provider or successful production completion is inferred from the presence of the UI. |
| AI Intelligence Centre | Authenticated on-demand GDELT discovery, deterministic event classification and sector hypotheses; typed Pydantic evidence/entity/event/impact/research-claim contracts and deterministic impact rollups. | Not an autonomous LLM research agent. No scheduled evidence ingestion, graph persistence, verified primary-source adjudication, company/instrument mapping or calibrated stock recommendations. Sector impact remains a hypothesis until company exposure is verified. |
| Research Lab | Local OHLCV CSV import/persistence, deterministic summary and on-demand NSE MCP research panel. | NSE MCP use is restricted to the repository's documented private educational/on-demand scope. It must not be repurposed as an automated market scanner or AI training pipeline. |
| Portfolio | Risk-based position sizing and portfolio/risk UI. | Not yet integrated with continuously refreshed holdings, corporate actions, live exposure or event-impact monitoring. |

## Important branch integration state

- PR #18 merged the stacked market-data, EOD ingestion and Android intelligence workspace changes into `main`.
- PR #12 contributes the typed evidence, entity, event, impact-edge and research-claim contracts plus deterministic impact rollups.
- PR #14 contributes live NSE MCP smoke validation, bounded retry behavior and a reviewed read-only tool allowlist; it remains a separate integration until its exact-head checks and conflicts are resolved.
- PRs #15–#17 and their prerequisite stack are included in PR #18 and have been closed as superseded.
- A visible screen or passing compile is not evidence of a live provider connection; runtime and source freshness remain separate acceptance criteria.

## Data-source and licence boundaries

- TejHQ's keyless endpoints provide NSE/BSE EOD OHLCV, full-market snapshots and corporate actions. Its published terms permit one individual's private research and private dashboard use, prohibit raw-data redistribution, and state that exchange terms continue to apply. The provider does not claim to license NSE/BSE data on the exchanges' behalf.
- Upstox V3 is the intended optional real-time feed, using the owner's own authorised account/token. Never report live status merely because a token is stored.
- Official NSE RSS feeds are suitable discovery inputs for corporate announcements, financial results, board meetings, corporate actions, shareholding and circulars. Preserve original filing links and verify material facts against the filing itself.
- GDELT is a discovery index, not primary-source verification. Preserve publisher URLs and apply rate limits, caching and deduplication.
- NSE MCP is not an unrestricted data-ingestion licence. Respect the repository's documented private educational/on-demand restrictions; do not persist its raw responses, use them for model training, or turn them into an automated scanner.
- Never scrape undocumented exchange endpoints or imply that a public web page grants redistribution rights.

## Not yet implemented / acceptance gaps

1. Durable, restart-safe scheduled EOD ingestion for NSE/BSE, with source freshness checks, deduplication, retries, provenance and persistent PostgreSQL storage.
2. Historical EOD backfill and a normalized point-in-time market database with corporate-action-aware adjustments and instrument identity history.
3. Verified sector benchmark historical returns and sector-relative ranking/rotation; a sector index watchlist alone is not this feature.
4. Full-universe scanner jobs with reproducible strategy versions, point-in-time data, liquidity filters, costs/slippage, out-of-sample tests and persisted signal lifecycle.
5. Persistent news/filing/macro evidence ingestion, source trust and freshness policies, document lineage and revision handling.
6. Canonical ISIN/company/instrument mapping and ambiguity review.
7. Evidence-backed event → economy/commodity → sector → company → instrument impact propagation with counter-evidence and confidence calibration.
8. RAG and specialist research orchestration with claim-level citations, deterministic verifier, bounded investigations and approval gates.
9. Durable research reports, alerts, watchlist/portfolio monitoring and user-visible source-health diagnostics.
10. End-to-end deployment and device validation with real owner-configured provider credentials.

## Current CI and build rule

- The exact-head GitHub Actions run is the build authority. Do not infer a green build from an earlier commit or from an individual passing job.
- A verified APK requires successful compile, lint, unit tests, APK identity/archive checks and emulator regression. The CI workflow uploads a debug APK artifact only after its APK verification step succeeds.
- No local Android build is claimed unless the Android SDK, Gradle toolchain and required dependencies are actually available and the build completes.
- Never label EOD data as live, a deterministic sector hypothesis as a forecast, or an unconfigured AI provider as connected.

## Required next implementation sequence

1. Complete exact-head CI and fix all failures.
2. Validate TejHQ EOD API response, NSE/BSE series filtering and Market Pulse rendering against live data in a controlled smoke test.
3. Add a durable scheduled ingestion worker backed by PostgreSQL; the public repository's GitHub Actions standard runners are a possible free EOD scheduler, but schedule execution is not guaranteed to be real-time and runs only from the default branch.
4. Add official NSE RSS and selected primary-source ingestion with provenance and source-health state.
5. Add verified historical sector-index returns and then point-in-time constituent-level sector analytics.
6. Build scanner and signal persistence only after the underlying universe/history pipeline is validated.
7. Extend the typed evidence/impact contracts into persistent ingestion, canonical entity resolution, temporal graph storage and claim-level verification before implementing model-generated causal analysis.
8. Complete emulator/device validation and publish only verified APKs.

No placeholder stock picks, fabricated events, fabricated prices or unsupported causal claims are permitted.
