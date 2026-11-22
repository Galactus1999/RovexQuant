# Quant AI — Android integration and EOD recovery

## Mobile API contract

The Android app uses the existing short-lived Quant app session:

- `GET /api/v1/app/ai/providers`
- `POST /api/v1/app/ai/chat`
- `POST /api/v1/app/ai/strategy-brief`

All three mobile routes require `Authorization: Bearer <Quant app session>`. They do not accept or expose `QUANT_AI_GATEWAY_TOKEN`. The older `/api/v1/ai/*` routes remain server-to-server routes protected by `X-Quant-AI-Gateway-Key`.

Provider API keys remain server-side. Configure one or more supported provider secrets in the backend environment. Also configure `TEJHQ_API_KEY` with a free TejHQ key for corporate-action-adjusted history; the keyless raw OHLCV endpoint is deliberately not used for strategy evidence. Optionally set the matching `<PROVIDER>_DEFAULT_MODEL` variable so the Android selector can prefill the model ID:

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
2. Retrieve adjusted daily OHLCV from TejHQ's documented `/v1/adjusted` endpoint using the backend-only free API key.
3. Validate raw OHLCV, adjusted close, cumulative adjustment factor, OHLC ordering, volume, unique trading dates, row count and requested date range.
4. Apply the cumulative split/bonus/dividend adjustment factor to OHLC and require at least 30 valid daily bars.
5. Calculate price indicators and trend evidence deterministically in Quant. Raw volume is explicitly excluded from relative-volume analysis because it is not adjusted.
6. Send only a compact evidence summary—not the raw market-data response—to the selected AI provider.
7. Return the validated adjusted daily bars, provenance, deterministic metrics, AI commentary and limitations to Android.

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
3. Configure `TEJHQ_API_KEY` on the backend. A free key is available through TejHQ email sign-in; no key is sent to Android.
4. Configure at least one supported AI provider API key on the backend and verify its model ID.
5. Re-deploy the backend.
6. Sign in to Quant, open **Scan**, select **Open AI data recovery**, choose a configured provider, enter a model ID and send a test prompt.
7. Test adjusted EOD recovery with a liquid NSE symbol and a period with at least 30 available trading sessions.
8. Confirm the UI labels the recovered dataset `END_OF_DAY_ADJUSTED`, shows the TejHQ source and latest trading date, and displays the data-rights/limitations notice.

The CI suite uses deterministic fake providers and data. It does not prove that a real provider API key, TejHQ upstream, Render deployment or physical Android device is working.
