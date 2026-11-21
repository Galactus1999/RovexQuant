# Quant

Independent, provider-agnostic Indian-market research and paper-trading platform. Research signals only; no broker order placement.

## Principles
- Zero-cost/local development mode using permitted historical/public datasets.
- Provider-neutral market-data contracts; optional future Zerodha Kite Connect and DhanHQ adapters.
- Deterministic strategies and risk controls are authoritative. AI explains supplied evidence and cannot override risk gates.
- Small modules, explicit ports/adapters, tests and versioned strategies.
- Provider-neutral AI gateway with Groq, DeepSeek, OpenRouter, Gemini and OpenAI-compatible chat adapters.

## Layout
- `android/`: Kotlin/Jetpack Compose client.
- `backend/`: FastAPI modular monolith.
- `docs/`: architecture, contracts, strategy catalogue, security and roadmap.

## AI gateway status
The backend now has a server-side chat-completions gateway, safe provider catalogue, named credential selection, provider-level fallback, bounded requests and credential redaction. Configure server environment secrets using `.env.example`; do not add keys to the APK or Git. The gateway stays disabled until `QUANT_AI_GATEWAY_TOKEN` is configured.

The current shared gateway token is for controlled server-to-server development only. Before connecting public mobile clients, implement per-user authentication, tenant-isolated encrypted BYOK credentials, budgets and rate limits. Provider keys are selected explicitly; Quant does not rotate keys to bypass quotas or rate limits.

## Current limitations
- No licensed live NSE/BSE feed is connected. Imported CSV is historical/local data and is never labelled live.
- No broker login, portfolio sync, order placement, automated trading or alert delivery is enabled.
- The Android client does not yet call the backend or AI gateway.
- The backtester is a long-only prototype and does not model all execution costs, market impact, partial fills, queue position, short borrow or derivatives margin.
- AI output is untrusted analysis and cannot authorize a trade or override deterministic risk controls.
- Use datasets only where their licence permits the intended analysis.
