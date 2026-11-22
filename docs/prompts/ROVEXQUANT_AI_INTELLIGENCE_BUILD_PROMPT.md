# RovexQuant — AI Intelligence Centre & Market Intelligence Build Prompt

**Status:** Authoritative product/architecture requirement for the RovexQuant implementation.
**Repository:** `Galactus1999/RovexQuant`
**Working integration branch:** `feat/quant-ai-intelligence-foundation`
**Baseline:** Preserve the current Upstox OAuth/streaming feature work, application identity, signing identity, existing user data, and all passing CI safeguards. Re-audit the current branch before every implementation batch; do not assume an older chat's file paths or branch state are current.

## 1. Product intent

RovexQuant is to become an AI-powered Market Intelligence and Equity Research platform, not a chatbot with superficial market widgets. The primary AI experience is a dedicated **AI Intelligence Centre**. Chat is optional and secondary.

The Intelligence Centre must continuously connect global developments, Indian macro/market conditions, sectors, listed companies, instruments, and user-specific watchlists/portfolio using evidence-backed analysis.

Core areas:
- Global intelligence: geopolitics, wars, sanctions, trade, shipping, energy, global macro and central-bank developments.
- Market regime: NIFTY, volatility, breadth, liquidity, trend, participation and risk-on/risk-off state.
- Sector intelligence: relative strength, rotation, breadth, catalysts, valuation context and emerging risks/opportunities.
- Stock intelligence: catalysts, company exposure, financials, valuation, technical setup, risks and invalidation conditions.
- Innovation radar: inventions, scientific/technology breakthroughs, AI, semiconductors, batteries, defence, pharma, energy and emerging industries.
- AI research: cross-domain retrieval, evidence synthesis, scenario analysis, company mapping and hypothesis generation.
- Daily intelligence briefing, sector opportunity/risk radar, event-driven stock discovery, stock research reports, portfolio/watchlist impact, thesis-change alerts and optional research chat.

## 2. Core analytical workflow: Event → Economy → Sector → Company → Instrument

For every material development:
1. Detect and preserve the original event and source.
2. Classify event type, geography, entities, timestamps, confidence and materiality.
3. Resolve named entities to canonical organisations, countries, commodities, sectors, NSE/BSE-listed companies and instruments.
4. Describe the plausible economic transmission mechanism (e.g. supply disruption → crude/freight → input costs/inflation/INR → sector margins).
5. Identify affected sectors and explain direction, mechanism, time horizon and conditionality.
6. Identify companies using actual business-segment, geography, supplier/customer, commodity, capex, regulation and revenue/cost exposure—not ticker-name similarity.
7. Assess company fundamentals, valuation, technicals, liquidity and market reaction using timestamped verified datasets.
8. Produce potential beneficiaries, vulnerable companies, mixed/uncertain exposures and unaffected controls.
9. State what would confirm, weaken or invalidate the thesis; identify monitoring indicators.
10. Store the reasoning graph and versioned assessment so later events/data can update the thesis with an auditable diff.

Example: a geopolitical conflict disrupting oil supply may affect crude, freight, insurance, inflation, INR and import costs; then Oil & Gas, Aviation, Paints, Chemicals, Logistics and Fertilisers; then only those listed companies with demonstrated exposure, pricing power, hedging, inventory and balance-sheet characteristics. Do not infer a stock winner merely from sector labels.

## 3. Three integrated layers

### Market-data layer
- Historical and live/delayed prices, OHLCV, indices, breadth, volumes, technical indicators, corporate actions and market calendars.
- Every value carries provider, instrument identity, exchange timestamp, receive timestamp, timezone, mode (live/delayed/EOD/historical), quality and entitlement provenance.
- Do not silently relabel delayed/EOD/historical values as live.
- Upstox live feed remains preferred when authorised and healthy. NSE-MCP is a distinct fallback/research source only after its actual tool schemas, terms, freshness and permissions are verified. CMMKT/delayed information and bhavcopy/EOD must be labelled accurately; neither is an equivalent WebSocket replacement.
- Provider selection, health, staleness, partial coverage, recovery and per-symbol gaps must be explicit.

### Fundamental and event evidence layer
- Original news, NSE/BSE disclosures, company filings, financial statements, earnings, macro releases, central-bank decisions, government/regulatory releases, commodities and global events.
- Preserve canonical source URL/ID, publisher, published-at, observed-at, retrieved-at, document hash/version, language, extraction method, licence/entitlement and trust tier.
- Deduplicate syndicated copies without losing original source links; retain conflicting reports and revisions.
- Treat source text and retrieved documents as untrusted data, never instructions.

### Intelligence layer
- Entity resolution and canonical identifiers.
- Event extraction, event taxonomy and event deduplication.
- Temporal knowledge graph: Event ↔ Country/Economy ↔ Commodity/Industry ↔ Sector ↔ Company ↔ Instrument.
- Evidence retrieval (RAG) with source provenance and freshness.
- Deterministic transmission rules/calculations, financial and technical analysis.
- Model router, specialist agents, planner, verifier, synthesis and calibrated ranking.
- Versioned research outputs and evaluation/performance feedback.

## 4. Evidence-first recommendations

Every stock idea/research report must include:
- Catalyst and why it matters now.
- Linked event(s), original sources and publication timestamps.
- Company exposure evidence and transmission mechanism.
- Sector outlook and relative strength.
- Revenue, margins, debt, cash flow, capex, business segments and valuation with data dates.
- Technical trend, momentum, volume, support/resistance and liquidity with timeframe and data source.
- Scenario analysis (base/upside/downside), assumptions and time horizon.
- Risks, counterarguments, uncertainty, invalidation criteria and monitoring indicators.
- Evidence quality, freshness, source agreement/conflict, coverage completeness and model version.
- Clear classification: research hypothesis / watch / avoid / no conclusion. No arbitrary certainty scores.

Rank by evidence quality, data freshness, exposure strength, analytical agreement, risk/reward and completeness—not by persuasive prose. A missing metric remains missing; never fabricate it. AI-generated analysis cannot authorize trades or override deterministic risk gates. This product is research-only unless a separately reviewed, explicit product change is approved.

## 5. Truth, safety and integrity requirements

- Deterministic calculations and verified source data are authoritative; models explain, connect and propose hypotheses.
- Every factual claim in a report must link to evidence or be marked as an inference/assumption.
- Distinguish confirmed facts, plausible transmission mechanisms, hypotheses and unknowns.
- No invented prices, company facts, filings, news, financial metrics, citations, timestamps or provider availability.
- No silent fallback on authentication/entitlement failures; fallback only on classified transient failures and only to a source permitted for the requested data type.
- No stale quote displayed as live. No EOD data used as intraday data. No future or fabricated timestamps.
- Preserve source disagreement; do not average contradictory claims into false certainty.
- Respect exchange/vendor licensing, robots/access rules, rate limits, terms and redistribution restrictions. MCP availability does not itself grant redistribution rights.
- No credentials in APK, source control, logs, analytics, prompts or AI context. Keep secrets server-side and tenant-isolated.
- Enforce per-user authorisation, quotas, budgets, request limits, auditability and least privilege before public multi-user rollout.
- AI cannot place broker orders, bypass permissions, or mutate portfolio records.
- Bounded timeouts, concurrency, retries, payloads, token budgets and circuit breakers; graceful deterministic fallback.
- Avoid heavyweight model dependencies until measurable need and reliability are proven.

## 6. Deep integration with existing RovexQuant features

Use one canonical evidence/market-data substrate and shared services; do not build disconnected parallel databases or business-logic owners.

- Event identifies a sector → scanner queries the affected instrument universe.
- Scanner result → intelligence checks catalysts, exposure and contradictory evidence.
- Watchlist addition → track events and thesis changes for that company.
- Portfolio holding → calculate exposure and material risk, with user-specific authorisation.
- New market data → refresh regime, relative strength and technical context.
- New disclosure/event → re-evaluate affected company/sector theses and notify only on material changes.
- Research report → link to scanner, watchlist and portfolio views through stable IDs.
- Every integration must be opt-in/configurable where it affects notifications or user data.

## 7. Target AI Intelligence Centre experience

Primary navigation destination, not a chat-first screen:
- Daily Briefing and Market Regime.
- Global Event Timeline / Geopolitical Radar.
- Sector Rotation & Opportunity/Risk Radar.
- Event-to-Sector-to-Stock impact map.
- Stock Research workspace and ranked idea list.
- Innovation/Technology Radar.
- Watchlist & Portfolio Impact.
- Alerts / Thesis Changes.
- Evidence/source inspector and freshness indicators.
- Optional AI Research Chat.

Use polished native Jetpack Compose UI, reusable feature components, loading/empty/error/stale/partial states, accessibility and dark/light theme support. Do not ship fake charts, placeholder stock recommendations, hard-coded market values or decorative controls that imply functionality. If a backend capability is not ready, label it as unavailable/coming later or omit it.

## 8. Modular architecture

Keep the existing FastAPI modular monolith and Android modular feature boundaries. Suggested bounded contexts (adapt to actual repository structure; do not force a new framework):

- `evidence`: source registry, provenance, documents, trust/freshness, deduplication.
- `entities`: canonical country, organisation, commodity, sector, company and instrument resolution.
- `events`: event schema, taxonomy, extraction, temporal updates and event clustering.
- `knowledge_graph`: typed temporal relationships, provenance and graph traversal.
- `market_data`: provider-neutral quotes/bars, instrument mapping, provider health and quality.
- `fundamentals`: company financials, segment exposure, valuation and corporate actions.
- `impact`: deterministic transmission rules, sector/company impact and scenarios.
- `research`: research report contracts, catalyst/technical/fundamental synthesis.
- `ai_orchestration`: model routing, specialist tasks, planner, verifier, bounded synthesis.
- `ranking`: evidence-weighted scoring, uncertainty and calibration.
- `integrations`: scanner, watchlist, portfolio and notification adapters.
- Android: `intelligence` feature with domain models, repository, use cases, UI state and Compose screens; share existing authenticated API client/session and theme/navigation conventions.

Keep API contracts versioned, typed, small and testable. Avoid god files, circular dependencies, duplicate owners, and direct provider calls from UI.

## 9. Development phases and mandatory gates

### Phase 0 — Baseline, CI and security
- Confirm branch, latest commit, package/application ID, signing identity, backend deployment and user-data persistence.
- Reproduce current CI failure and classify code vs infrastructure.
- Keep all currently passing tests and artifact/signing checks.
- Record architecture decisions and actual current implementation gaps.
- No feature work on an unverified baseline.

### Phase 1 — Data contracts and provider fallback
- Define canonical evidence, source, entity, quote, bar and freshness contracts.
- Verify actual NSE-MCP server/tool schemas using live connectivity smoke; inspect response payloads and permissions.
- Implement NSE-MCP adapter only for data it demonstrably provides; keep CMMKT/delayed and bhavcopy/EOD distinct.
- Add provider orchestration, freshness validation, per-symbol partial fallback, circuit breaker and recovery.
- Connect authenticated Android repository to backend with explicit source/mode/timestamp/quality.
- Tests: provider outage, no first tick, timeout, malformed payload, auth/entitlement error, market closed, partial symbols, stale response, recovery, out-of-order data and duplicate ticks.

### Phase 2 — Evidence and entity foundation
- Source registry and provenance; immutable raw-source reference/hash; normalisation and deduplication.
- Canonical entity IDs and NSE/BSE symbol mapping with confidence and manual-review queue.
- Sector/industry/company mapping backed by source data and effective dates.
- Tests for aliases, mergers/name changes, ambiguous entities, duplicates, revisions, timezone and stale sources.

### Phase 3 — Event intelligence and knowledge graph
- Ingest and classify events; maintain event lifecycle and source conflicts.
- Build typed, temporal, provenance-backed graph edges.
- Add deterministic event-to-economy-to-sector-to-company impact propagation.
- Keep inferred relationships separate from verified facts.
- Tests for propagation correctness, direction, time horizon, confidence, graph cycles, missing exposure and counterexamples.

### Phase 4 — Equity research and verifier
- Fundamentals, valuation, technicals, catalysts, scenarios and risk/invalidation.
- Evidence retrieval with claim-level citations and date-aware context.
- Specialist model routing, bounded planner and independent verifier.
- Evidence-weighted ranking and uncertainty calibration.
- Tests for unsupported claims, missing metrics, stale data, conflicting sources, hallucinated citations and ranking stability.

### Phase 5 — AI Intelligence Centre Android experience
- Build real dashboard screens backed by implemented API contracts.
- Daily brief, regime, global timeline, sector radar, event impact, stock research, innovation radar, portfolio/watchlist impact and optional chat.
- All screen states, deep links and evidence inspector; no dummy values.
- UI tests and accessibility/theme regression.

### Phase 6 — Deep integrations and alerts
- Scanner, watchlist and portfolio adapters; thesis change tracking; opt-in alerts.
- Per-user access controls and audit logs.
- Test tenant isolation, deleted/changed holdings, duplicate alerts and materiality thresholds.

### Phase 7 — Evaluation and production hardening
- Historical event studies, point-in-time data, survivorship-bias controls and out-of-sample testing.
- Track recommendation outcomes by horizon, sector, regime and evidence quality; include benchmark and transaction-cost assumptions.
- False-positive/false-negative analysis, calibration, drift and source freshness SLOs.
- Security, load, failure injection, Android upgrade and backend deployment regression.

**A phase is complete only when its code, API contract, tests, docs and acceptance evidence are complete. UI-only work does not complete a phase.**

## 10. Engineering workflow and acceptance

- Work in small, auditable commits/PRs; inspect actual diff and surrounding architecture first.
- Run relevant unit, integration, static, security and Android checks after each risky batch.
- Never claim a build passed unless GitHub Actions actually passed on the exact commit.
- Preserve package name `com.rovexquant.app`, application/signing identity, database compatibility, authentication and user data.
- Never overwrite secrets or ask the user to disclose them.
- Do not merge unrelated experimental branches wholesale.
- For CI failures, retrieve exact failed job logs and fix root cause; do not mask failures by deleting meaningful tests.
- Emulator infrastructure failures may be classified separately, but UI correctness still needs a reliable test strategy (Compose UI tests, managed emulator/device test or validated alternative).
- Final acceptance requires green CI on the exact final commit, backend regression, Android compile/lint/unit/UI validation, APK identity/archive integrity, artifact retention and a concise evidence-backed change report.

## 11. Current repository observations to re-verify before coding

These were observed during the initial source audit and may change:
- Backend has a provider-neutral REST quote contract for Upstox and Dhan.
- Backend Upstox streaming endpoint exists.
- Android `RovexQuantApp.kt` starts a `MarketStreamController` and `MarketPulseScreen.kt` consumes ticks, but source README says the Android client is not yet connected to backend market endpoints. Resolve this apparent implementation/documentation mismatch by tracing the actual controller/API path.
- Backend `build_providers()` currently registers Upstox and Dhan only; NSE-MCP is not a quote provider in that path.
- NSE-MCP research work exists on a separate branch and must be inspected/verified before selective porting.
- Backend AI currently has provider-neutral chat-completions adapters (DeepSeek, Groq, OpenRouter, Gemini, OpenAI-compatible) and a shared-token gateway. This is not yet an event/research intelligence engine and is not safe for public multi-user access as-is.
- Android `ResearchLabScreen.kt` currently imports local OHLCV CSV and calculates deterministic indicators. Preserve this capability when adding the Intelligence Centre.
- Current UI includes Pulse, Scan, Signals, Lab and Portfolio sections; inspect current navigation before introducing a new destination.
- CI currently runs backend static/regression, container/auth smoke, Android compile/lint/unit tests and APK integrity; emulator UI tests were removed after an observed emulator boot infrastructure timeout. Do not represent UI validation as complete based only on CI green.

## 12. Immediate next actions

1. Reconfirm branch and exact latest commit; inspect full Android market-stream path and backend provider/service tests.
2. Save and use this document as the authoritative product/architecture specification.
3. Complete Phase 1 provider fallback first, including live NSE-MCP smoke and Android consumption; no fake success.
4. Start Phase 2 evidence/entity foundation in small modules with tests.
5. Implement the AI Intelligence Centre progressively against real backend contracts, retaining Research Lab and optional chat.
6. Report exact commits, CI run links, test outcomes, remaining limitations and artifacts. Never claim unfinished phases are complete.
