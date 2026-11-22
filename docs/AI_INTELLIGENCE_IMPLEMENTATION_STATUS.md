# AI Intelligence implementation status

**Working branch:** `feat/quant-ai-intelligence-foundation`
**Last reviewed:** 2026-11-22
**Authoritative requirement:** [AI Intelligence Centre build prompt](prompts/ROVEXQUANT_AI_INTELLIGENCE_BUILD_PROMPT.md)

## Existing capabilities observed before this work

| Area | Observed implementation | Gap |
|---|---|---|
| AI provider gateway | Server-side chat-completions adapter for DeepSeek, Groq, OpenRouter, Gemini and OpenAI-compatible OpenAI; named credentials and bounded request handling | Chat only; shared gateway token is not a public multi-tenant auth design |
| Android AI experience | Existing Research Lab and other research screens | No dedicated AI Intelligence Centre; Android does not call AI gateway |
| Market data | Upstox/Dhan REST quote adapters and an Upstox streaming backend path | NSE-MCP not registered in quote-provider selection; Android end-to-end provider path must be traced and verified |
| Technical research | Deterministic indicators, backtesting prototype, local OHLCV CSV research | No event-linked fundamental research engine |
| Evidence provenance | No complete source/evidence domain service observed | No source registry, document lineage, freshness policy or claim-level citations |
| Entity resolution | Existing instrument master utilities | No canonical cross-domain entity-resolution service |
| Event/impact graph | Not implemented | No event → economy → sector → company impact graph |
| Stock research | Deterministic scanner prototypes and research UI | No complete evidence-backed stock research report |
| Watchlist/portfolio intelligence | Portfolio risk calculator and app navigation | No event-driven thesis monitoring or exposure-impact pipeline |

## Changes committed in this foundation batch

- Added `backend/src/rovexquant/intelligence/contracts.py`.
- Added immutable, extra-field-forbidden Pydantic contracts for:
  - Evidence provenance, source class, trust tier, licence scope, content hash and timezone-aware timestamps.
  - Canonical entity references, aliases, external identifiers, mapping evidence and effective dates.
  - Event lifecycle, involved entities, evidence references, materiality and extractor version.
  - Typed temporal impact edges with direction, horizon, assumptions, confidence and evidence.
  - Research claims that distinguish observed facts, deterministic calculations, inferences and hypotheses.
- Added tests for provenance requirements, timezone integrity, event evidence, entity validity periods and hypothesis/fact classification rules.
- Added the authoritative product/build prompt and architecture document.
- Linked both from README.

## Explicitly not implemented yet

These files establish contracts and architectural constraints; they do not claim that the following capabilities exist:
- Live ingestion of news, filings, macro or NSE-MCP events.
- Source trust scoring, deduplication, document storage or revision tracking.
- Entity resolution against NSE/BSE companies and company segment exposure.
- Graph persistence, graph traversal or event impact propagation.
- Financial statement ingestion, valuation or fundamental scoring.
- AI specialist orchestration, RAG, claim verification or evidence-weighted ranking.
- Android AI Intelligence Centre UI, real API data binding, chat integration or alerts.
- Watchlist/portfolio event monitoring.

## Next implementation slice

1. Verify CI for the contracts and test batch.
2. Implement source registry and evidence ingestion adapters behind explicit source/licensing policies.
3. Implement canonical entity mapping with ambiguity and manual-review states.
4. Build a minimal event lifecycle and persistence layer.
5. Produce one evidence-linked deterministic impact assessment end-to-end before introducing model-generated causal reasoning.
6. Expose authenticated, versioned API contracts; then bind the Android Intelligence Centre to real responses.

No placeholder stock picks, fabricated events, or fake market metrics are permitted in the UI.
