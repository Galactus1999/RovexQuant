# Quant AI — Android integration and EOD recovery

## Mobile API contract

The Android app uses the existing short-lived Quant app session:

- `GET /api/v1/app/ai/providers`
- `POST /api/v1/app/ai/chat`
- `POST /api/v1/app/ai/strategy-brief`

All three mobile routes require `Authorization: Bearer <Quant app session>`. They do not accept or expose `QUANT_AI_GATEWAY_TOKEN`. The older `/api/v1/ai/*` routes remain server-to-server routes protected by `X-Quant-AI-Gateway-Key`.

Provider API keys remain server-side. Configure one or more supported provider secrets in the backend environment. Optionally set the matching `<PROVIDER>_DEFAULT_MODEL` variable so the Android selector can prefill the model ID:

- `GROQ_API_KEY` / `GROQ_API_KEYS_JSON`
- `DEEPSEEK_API_KEY` / `DEEPSEEK_API_KEYS_JSON`
- `OPENROUTER_API_KEY` / `OPENROUTER_API_KEYS_JSON`
- `GEMINI_API_KEY` / `GEMINI_API_KEYS_JSON`
- `OPENAI_API_KEY` / `OPENAI_API_KEYS_JSON`

The Android AI workspace asks for the provider's model ID because model catalogues change. Optional cross-provider fallback requires both an explicitly selected fallback provider and that provider's `<PROVIDER>_FALLBACK_MODEL` environment variable. Same-provider key rotation is not used to evade rate limits.

Do not put provider keys or `QUANT_AI_GATEWAY_TOKEN` in Android resources, BuildConfig, assets, local preferences, or APKs. Do not send secrets in chat.

## Final strategy-stage data recovery

When the local CSV import is unavailable, the AI workspace can call the authenticated strategy-brief endpoint. Its data path is:

1. Request a symbol, exchange and bounded date range.
2. Retrieve daily OHLCV from the existing TejHQ EOD adapter using its documented keyless `/v1/ohlcv` endpoint.
3. Validate the returned OHLC values, volume, unique trading dates, row count and requested date range in the existing TejHQ adapter.
4. Require at least 30 valid daily bars.
5. Calculate indicator and price/volume evidence deterministically in Quant.
6. Send only a compact evidence summary—not the raw market-data response—to the selected AI provider.
7. Return the validated daily bars, provenance, deterministic metrics, AI commentary and limitations to Android.

The recovered bars are displayed as EOD evidence; this endpoint does not write them into the local CSV store or convert them to intraday candles.

### Hard limitations

- TejHQ source is end-of-day daily data. It is not a live feed and cannot support scalping/intraday analysis.
- The AI model does not invent missing bars or fetch arbitrary URLs. If TejHQ or the AI provider fails, the request reports an error; no synthetic market data is generated.
- NSE MCP responses are not used as AI training input or as an AI data-recovery source. Existing NSE MCP restrictions remain in force.
- This implementation is for private personal research. TejHQ's data terms prohibit raw-data redistribution; exchange terms may independently apply. Do not expose recovered raw bars or derived data to other users or use the integration commercially without obtaining the required provider and exchange rights.
- AI output is research commentary, not a signal, trade instruction, or override for deterministic strategy and risk controls.

## Operational checks

Before using the Android AI workspace:

1. Deploy the backend with HTTPS and persistent configuration.
2. Configure the app-session credentials and signing key as already required by mobile login.
3. Configure at least one supported provider API key on the backend and verify its model ID.
4. Re-deploy the backend.
5. Sign in to Quant, open **AI**, select a configured provider, enter a model ID and send a test prompt.
6. Test EOD recovery with a liquid NSE symbol and a period with at least 30 available trading sessions.
7. Confirm the UI labels the recovered dataset `END_OF_DAY`, shows the TejHQ source and latest trading date, and displays the data-rights/limitations notice.

The CI suite uses deterministic fake providers and data. It does not prove that a real provider API key, TejHQ upstream, Render deployment or physical Android device is working.
