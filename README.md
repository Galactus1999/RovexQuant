# Quant

Independent, provider-agnostic Indian-market research and paper-trading platform. Research signals only; no broker order placement.

## Principles
- Zero-cost/local development mode using permitted historical/public datasets.
- Provider-neutral market-data contracts with explicit provider-specific instrument mappings.
- Deterministic strategies and risk controls are authoritative. AI explains supplied evidence and cannot override risk gates.
- Small modules, explicit ports/adapters, tests and versioned strategies.
- Provider-neutral AI gateway with Groq, DeepSeek, OpenRouter, Gemini and OpenAI-compatible chat adapters.
- AI Intelligence Centre is an evidence-first market intelligence and equity research product; chat is secondary.

## Architecture references
- [Authoritative AI Intelligence Centre build prompt](docs/prompts/ROVEXQUANT_AI_INTELLIGENCE_BUILD_PROMPT.md)
- [AI Intelligence architecture](docs/AI_INTELLIGENCE_ARCHITECTURE.md)
- [AI Intelligence implementation status](docs/AI_INTELLIGENCE_IMPLEMENTATION_STATUS.md)

The build prompt is a maintained product/engineering requirement. Use it when planning and reviewing every AI, market-data, evidence, research, scanner, watchlist and portfolio change. Update it only through an explicit, reviewed product decision.

## Layout
- `android/`: Kotlin/Jetpack Compose client.
- `backend/`: FastAPI modular monolith.
- `docs/`: architecture, contracts, strategy catalogue, security and roadmap.

## Market data integration status
The backend has provider-neutral quote contracts and REST snapshot adapters for Upstox Full Market Quotes V3 and Dhan market quotes. Provider order is explicit; failover is allowed only for transient provider/network failures. Credential failures and invalid requests do not silently switch accounts. Quotes include provider, exchange timestamp, receive timestamp and quality. Missing timestamps are never labelled live.

For a personal development setup, configure `QUANT_MARKET_GATEWAY_TOKEN` and `UPSTOX_ANALYTICS_TOKEN` in the backend secret environment. Dhan is optional and requires `DHAN_CLIENT_ID` plus `DHAN_ACCESS_TOKEN`; its Data API entitlement and pricing apply. Use `POST /api/v1/market/quotes` with provider-specific instrument mappings and `GET /api/v1/market/providers` to inspect safe configuration status.

**This is a REST snapshot integration, not yet the persistent Upstox V3 WebSocket streaming service.** The Android client is not yet connected to these endpoints. Do not embed the market gateway token or provider credentials in the APK. Before public multi-user access, add per-user authentication, tenant-isolated encrypted credential storage, account ownership checks, quotas, audit controls and provider/exchange data licensing approval.

## AI gateway status
The backend has a server-side chat-completions gateway, safe provider catalogue, named credential selection, provider-level fallback, bounded requests and credential redaction. Configure server environment secrets using `.env.example`; do not add keys to the APK or Git. The gateway stays disabled until `QUANT_AI_GATEWAY_TOKEN` is configured.

The current shared gateway token is for controlled server-to-server development only. Before connecting public mobile clients, implement per-user authentication, tenant-isolated encrypted BYOK credentials, budgets and rate limits. Provider keys are selected explicitly; Quant does not rotate keys to bypass quotas or rate limits.

The initial AI Intelligence foundation adds typed, provenance-first evidence, entity, event, impact-edge and research-claim contracts under `backend/src/rovexquant/intelligence/`. These are domain contracts only; source ingestion, entity resolution, graph persistence, impact computation, research orchestration and Android Intelligence Centre screens are not yet complete. See the implementation status document.

## Current limitations
- No verified licensed live NSE/BSE feed is connected end-to-end to the Android UI. Backend quote adapters require valid credentials and permitted data entitlements.
- NSE-MCP is not yet registered as a normalized market quote provider. Verify its actual tool schemas, data modes, freshness and licensing before using it as a fallback.
- No broker login, portfolio sync, order placement, automated trading or alert delivery is enabled.
- The Android client does not yet call the AI gateway or evidence intelligence APIs.
- The backtester is a long-only prototype and does not model all execution costs, market impact, partial fills, queue position, short borrow or derivatives margin.
- AI output is untrusted analysis and cannot authorize a trade or override deterministic risk controls.
- Use datasets only where their licence permits the intended analysis.
