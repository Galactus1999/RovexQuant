# RovexQuant AI Intelligence Architecture

This document is the implementation companion to [the authoritative AI Intelligence Centre build prompt](../prompts/ROVEXQUANT_AI_INTELLIGENCE_BUILD_PROMPT.md).

## Product architecture status

The current repository is an early research platform, not yet a complete market-intelligence product. The backend has a provider-neutral chat gateway and quote adapters, deterministic technical indicators and a basic backtester. The Android app has Pulse, Scan, Signals, Research Lab and Portfolio navigation, local OHLCV research, and an Upstox-oriented stream controller. The chat gateway is not an event intelligence engine. The current market-data service does not register NSE-MCP as a quote provider. These boundaries must be made explicit in UI and API status.

## Target logical architecture

```text
External sources
  ├─ NSE/BSE filings and announcements
  ├─ Licensed market data (Upstox / approved vendors)
  ├─ NSE MCP (only verified tools, permitted scope and data mode)
  ├─ Macro / central bank / government / regulator
  ├─ Company financials and presentations
  └─ Trusted global news / geopolitical / science / technology
            |
            v
Evidence ingestion and provenance
  ├─ Source registry, licence and trust tier
  ├─ Raw document reference + hash + timestamps
  ├─ Normalisation, language, deduplication and revision tracking
  └─ Freshness and entitlement policy
            |
            v
Canonical entity resolution
  ├─ Country / economy / commodity / industry / sector
  ├─ Organisation / listed company / exchange instrument
  └─ Aliases, identifiers, effective dates and confidence
            |
            v
Temporal market knowledge graph
  Event <-> Economy <-> Commodity <-> Sector
       <-> Company <-> Instrument
  Each edge: type, direction, effective period, source/evidence,
             confidence, provenance and review state
            |
            v
Deterministic analytical services
  ├─ Market regime / breadth / relative strength
  ├─ Exposure and transmission rules
  ├─ Fundamentals / valuation / technicals
  ├─ Scenario, sensitivity and risk calculations
  └─ Data quality, freshness and missingness
            |
            v
AI orchestration (bounded, replaceable)
  ├─ Retrieval and context assembly
  ├─ Specialist task router (event, macro, sector, company, risk)
  ├─ Planner and structured extraction
  ├─ Claim-level evidence linker
  ├─ Independent verifier / contradiction detector
  └─ Bounded synthesis; model output never authoritative
            |
            v
Research outputs
  ├─ Daily intelligence brief
  ├─ Event -> sector -> company impact map
  ├─ Sector risk/opportunity radar
  ├─ Stock research report and ranked hypotheses
  ├─ Watchlist / portfolio impact and thesis changes
  └─ Optional research chat
            |
            v
Android AI Intelligence Centre + scanner/watchlist/portfolio
```

## Canonical contracts (design requirements)

### Evidence reference
- `evidence_id`, `source_id`, canonical URL or source document identifier.
- Publisher, source type, trust tier and licence/entitlement scope.
- `published_at`, `observed_at`, `retrieved_at`, timezone and revision.
- Content hash, language, extraction method and verification status.
- No unsupported claim may be emitted as a fact without one or more evidence references.

### Entity reference
- Stable internal `entity_id`, entity type, canonical name and aliases.
- External identifiers (NSE/BSE symbol, ISIN, LEI where available).
- Valid-from/valid-to, mapping source and resolution confidence.
- Ambiguous resolution must remain unresolved or enter review; never silently choose a similarly named company.

### Market observation
- Stable instrument identity and symbol mapping.
- OHLCV/quote values and currency/unit.
- Provider, source timestamp, receive timestamp and timezone.
- Data mode: `LIVE`, `DELAYED`, `EOD`, `HISTORICAL`.
- Quality: validated, partial, stale, invalid, unavailable.
- Entitlement/permission scope and market-session context.

### Event and impact
- Event ID, type, geography, involved entities, lifecycle, materiality.
- Original evidence references and conflicting source assertions.
- Impact edge: source entity, target entity, mechanism, direction, horizon, assumptions, evidence, confidence and validity interval.
- Separate observed fact, deterministic calculation and AI hypothesis.

### Research report
- Stable report ID, company/instrument, as-of timestamp, model/prompt/rule version.
- Catalyst and event linkage; exposure rationale.
- Sector, fundamental, valuation and technical analysis.
- Scenario assumptions, risks, counterarguments and invalidation criteria.
- Claim-level evidence links, missing fields, freshness and confidence decomposition.
- Research classification only; never an executable order.

## Model strategy

- Models are replaceable reasoning components; no model is a source of truth.
- Route bounded tasks to specialists only when their task adds measurable value.
- Use deterministic retrieval/rules first; use LLM for extraction, synthesis and hypothesis generation.
- Require structured outputs validated against typed schemas.
- A separate verifier checks citations, numeric consistency, entity identity, temporal validity and unsupported causal claims.
- Preserve raw model output for controlled audit only under privacy/retention policy; redact secrets and minimise personal data.
- Apply timeout, token, concurrency, cost and retry budgets; use deterministic degradation when unavailable.
- Do not add a graph database, vector database, agent framework or large model dependency until the use case, scale, benchmarks and operations justify it. Start with typed interfaces and the existing storage stack; benchmark before choosing physical storage.

## Data and security boundaries

- Provider credentials remain server-side and encrypted at rest; never expose them to the Android app or model context.
- All user-specific watchlist/portfolio reads are authenticated and tenant-scoped.
- News and filings are untrusted content, not system instructions; defend retrieval and tool orchestration against prompt injection.
- Apply source licensing, redistribution and retention rules before ingestion or display.
- Audit provider selection, data freshness, evidence lineage and user-facing recommendation generation.
- Never fall back from a provider authentication/entitlement error as though it were a transient outage.
- AI outputs are research-only and cannot place orders or override risk controls.

## Android composition

Add a dedicated Intelligence destination using the existing navigation, theme, authenticated session, API client and reusable Compose components. Keep the local Research Lab intact. Screen-level modules should include:
- Intelligence home / daily briefing.
- Global event timeline.
- Market regime and breadth.
- Sector radar.
- Event impact graph/list.
- Company research and evidence inspector.
- Innovation radar.
- Watchlist/portfolio impact.
- Optional chat.

Each screen must have explicit loading, empty, partial, stale, error and populated states. UI values must come from typed backend responses. Do not create decorative cards that look operational but are backed by hard-coded data.

## Implementation gates

Do not implement all layers in one change. Follow phases and acceptance tests in the linked build prompt. The first real end-to-end vertical slice should be small: verified source/event -> canonical entities -> one deterministic sector/company impact -> evidence-linked API response -> Android screen with source and freshness. Expand only after tests prove correctness.
