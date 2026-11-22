# Quant

Independent, provider-agnostic Indian-market research and paper-trading platform. Research signals only; no broker order placement.

## Principles
- Zero-cost/local development mode using permitted historical/public datasets.
- Provider-neutral market-data contracts with explicit provider-specific instrument mappings.
- Deterministic strategies and risk controls are authoritative. AI explains supplied evidence and cannot override risk gates.
- Small modules, explicit ports/adapters, tests and versioned strategies.
- Provider-neutral AI gateway with Groq, DeepSeek, OpenRouter, Gemini and OpenAI-compatible chat adapters.

## Layout
- `android/`: Kotlin/Jetpack Compose client.
- `backend/`: FastAPI modular monolith.
- `docs/`: architecture, contracts, strategy catalogue, security and roadmap.

## Market data integration status
The backend has provider-neutral quote contracts and REST snapshot adapters for Upstox Full Market Quotes V3 and Dhan market quotes. Provider order is explicit; failover is allowed only for transient provider/network failures. Credential failures and invalid requests do not silently switch accounts. Quotes include provider, exchange timestamp, receive timestamp and quality. Missing timestamps are never labelled live.

For a personal development setup, configure `QUANT_MARKET_GATEWAY_TOKEN` and `UPSTOX_ANALYTICS_TOKEN` in the backend secret environment. Dhan is optional and requires `DHAN_CLIENT_ID` plus `DHAN_ACCESS_TOKEN`; its Data API entitlement and pricing apply. Use `POST /api/v1/market/quotes` with provider-specific instrument mappings and `GET /api/v1/market/providers` to inspect safe configuration status.

The Android app calls backend app-session authentication, official NSE index-instrument discovery, the authenticated market WebSocket bridge, the on-demand NSE MCP catalogue/tool endpoints and the authenticated mobile AI endpoints. The AI workspace is available from the terminal navigation and from the scanner's final-stage data-recovery action. The Android client does **not** yet consume the REST quote gateway. The backend Upstox V3 stream bridge and Protobuf decoder exist, but the current bridge sends the subscription as a text frame while Upstox V3 requires a binary frame. This is a known protocol defect; live streaming must not be claimed until it is corrected and verified with an entitled credential and Android device. TejHQ EOD endpoints are backend-only and are not live data. Do not embed the market gateway token or provider credentials in the APK. Before public multi-user access, add per-user identity, tenant-isolated encrypted credential storage, object authorization, quotas, audit controls and provider/exchange data licensing approval.

## Personal EOD research data
The backend includes an authenticated TejHQ EOD research adapter. Keyless endpoints provide raw daily OHLCV, full-market snapshots and corporate actions; a free TejHQ key enables corporate-action-adjusted prices and delivery history. Configure `TEJHQ_API_KEY` only in the backend environment. The AI strategy-recovery endpoint requires adjusted prices and applies the cumulative split/bonus/dividend factor to OHLC; raw volume is excluded from relative-volume inference. All recovered bars are EOD, not live or intraday. See [Research data sources](docs/RESEARCH_DATA_SOURCES.md).

TejHQ's published data terms limit its free data to one person's personal research and prohibit using it in a product for other people or a business, and prohibit raw-data redistribution. This integration is therefore for the owner's private research only. Do not expose or redistribute TejHQ responses through a public Quant service without the required data rights. NSE MCP is a separate informational/educational research tool, not a backend market-data feed.

## AI gateway status
The backend has a server-side chat-completions gateway, safe provider catalogue, named credential selection, provider-level fallback, bounded requests and credential redaction. Android uses `/api/v1/app/ai/providers`, `/api/v1/app/ai/chat` and `/api/v1/app/ai/strategy-brief`, authenticated by the existing short-lived Quant app session. These mobile routes do not require the shared `QUANT_AI_GATEWAY_TOKEN`; provider API keys remain exclusively on the backend. The older `/api/v1/ai/*` routes remain server-to-server routes protected by `X-Quant-AI-Gateway-Key`. Configure provider secrets using `.env.example` and never add keys to the APK or Git.

The AI workspace supports chat and an EOD recovery workflow from the scanner's final strategy stage. The recovery path requires `TEJHQ_API_KEY` plus at least one configured AI provider. It returns corporate-action-adjusted EOD bars and an evidence-bounded AI review. It does not create intraday data, synthetic prices, validated trading signals or broker orders. Current app-session auth is a single-owner pilot, not multi-user tenant isolation. Before public multi-user access, implement per-user identity, tenant-isolated encrypted credential storage, object authorization, budgets, audit controls and provider/exchange data licensing approval. Provider keys are selected explicitly; Quant does not rotate keys to bypass quotas or rate limits.

## Current limitations
- No licensed live NSE/BSE feed is connected to the Android UI. Backend quote adapters require valid user credentials and permitted data entitlements.
- No broker login, portfolio sync, order placement, automated trading or alert delivery is enabled.
- The Android client calls app auth, index-master, WebSocket, NSE MCP and mobile AI endpoints; it does not yet call the REST quote gateway.
- The backtester is a long-only prototype and does not model all execution costs, market impact, partial fills, queue position, short borrow or derivatives margin.
- AI output is untrusted analysis and cannot authorize a trade or override deterministic risk controls.
- Use datasets only where their licence permits the intended analysis.
