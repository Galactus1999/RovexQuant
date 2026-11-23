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

The Android AI Studio asks for the provider's model ID because model catalogues change. It exposes an explicit connection test, removal of owner-stored app credentials, and selectable cross-provider fallback. A fallback is offered only when that provider has both a configured credential and a server-side `<PROVIDER>_FALLBACK_MODEL`; only classified transient failures trigger fallback. Authentication, entitlement and invalid-request errors do not silently switch providers. Same-provider key rotation is not used to evade rate limits.

Do not put provider keys or `QUANT_AI_GATEWAY_TOKEN` in Android resources, BuildConfig, assets, local preferences, or APKs. Do not send secrets in chat. The AI workspace also embeds the existing read-only NSE MCP research panel; attaching a result to a model request is an explicit user action and sends that selected response to the configured AI provider for inference. Review the exchange/provider data-use terms before doing so.

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
3. Configure `TEJHQ_API_KEY` on the backend. A free key is available through TejHQ email sign-in; no key is sent to Android. This is required for adjusted EOD recovery.
4. Configure at least one supported AI provider API key and optional `<PROVIDER>_DEFAULT_MODEL` on the backend if you want chat and AI commentary. Data recovery itself remains available without an AI provider.
5. Re-deploy the backend.
6. Sign in to Quant, open **Scan**, select **Open AI data recovery**, enter a symbol and date range, then run adjusted EOD recovery. Configure/select an AI provider and model to receive commentary.
7. Test adjusted EOD recovery with a liquid NSE symbol and a period with at least 30 available trading sessions.
8. Confirm the UI labels the recovered dataset `END_OF_DAY_ADJUSTED`, shows the TejHQ source and latest trading date, and displays the data-rights/limitations notice. If the AI provider is unavailable, the adjusted bars and deterministic metrics should still be returned with an AI-unavailable warning.

The CI suite uses deterministic fake providers and data. It does not prove that a real provider API key, TejHQ upstream, Render deployment or physical Android device is working.


## AI Intelligence Centre — first backend slice

The Android navigation now includes a dedicated **Intel** workspace, separate from the optional **Chat** workspace. It calls an authenticated on-demand discovery endpoint at
`GET /api/v1/app/intelligence/events`. It queries the GDELT DOC API for recent
global-news articles, retains source URLs and GDELT seen timestamps, deduplicates
exact normalized headlines, and applies transparent deterministic event tags and
sector-exposure hypotheses.

The first taxonomy covers conflict/war, monetary policy, geopolitical trade,
commodity supply, technology/innovation, regulation/policy and corporate
developments. Sector direction is expressed as a potential exposure channel
(`POTENTIAL_SUPPORTIVE`, `POTENTIAL_NEGATIVE`, `MIXED`), not a stock-return
prediction. Each result reports its rule-based classification method and an
uncalibrated heuristic score. Unclassified headlines receive no sector mapping.

This endpoint is intentionally not a stock recommender. The current instrument
master resolves index instruments, not a complete canonical listed-company and
ISIN universe. The API therefore reports `stock_mapping_status=NOT_RESOLVED`
instead of guessing company/ticker matches. Company-level supply-chain, revenue,
geography and commodity exposure mapping is a subsequent prerequisite.

GDELT is used for discovery only. Its article seen timestamp is not asserted to be
the publisher's publication time; material claims must be checked against original
publisher, exchange or regulator disclosures. The endpoint is on-demand, not a
durable collector or scheduled monitoring service. No continuous monitoring,
persistent event history, live price correlation or recommendation performance is
claimed in this first slice.


### Android analytical panel

The Intel screen is backed by a dedicated repository method and
`IntelligenceCentreViewModel`. It supports query and 1d/3d/7d windows, latest
request cancellation, loading/error states, event-category filtering, sector
transmission explanations and opening original publisher URLs. It renders the
source's seen timestamp and explicitly labels the rule-match score as a heuristic,
not a probability. The screen does not fabricate sample live events; the debug
instrumentation host supplies a deterministic fixture only for UI regression.


## AI Studio provider and research-tool controls

The Android AI Studio exposes provider setup for DeepSeek, Groq, OpenRouter, Gemini and OpenAI-compatible chat completions. The selected provider has a direct link to its official API-key console. App-owned credentials are stored and removed through the authenticated backend vault; environment-managed provider credentials are not deleted by the app-key removal action.

The explicit **Test connection** control sends a minimal completion request. Provider usage or billing may apply. The UI reports the provider/model returned by the backend; a saved key alone is not treated as proof of connectivity.

Cross-provider fallback is opt-in and limited to three selected providers. A fallback appears only when the backend reports both a configured credential and a server-side `<PROVIDER>_FALLBACK_MODEL`. The gateway's transient-error classification is authoritative. Authentication, entitlement, invalid-request and policy errors do not trigger silent provider switching.

The research-tool panel can fetch:
- The latest persisted NSE EOD snapshot, with trading date and breadth/mover fields. It is never labelled as a live quote.
- The persisted macro research summary, preserving series key, observation period, frequency, unit and provider status.
- The durable intelligence archive, preserving event IDs, source domains/URLs, timestamps, independent-source counts, mapping status and limitations.
- Read-only NSE MCP tool results, only after the user selects and executes a permitted tool.

Each tool result is inserted into the draft as bounded context. It is not sent to an external model until the user explicitly sends a message or generates a research briefing. Provider usage/billing may apply to model requests.

The separate **AI research briefing** action synthesizes the evidence already attached to the draft. Its system prompt requires market/macro/event source references, distinguishes facts from hypotheses, preserves EOD/delayed/live modes, and prohibits fabricated prices, sources, causal claims and trade instructions. A client-side reference audit checks returned event IDs, macro series/period markers, EOD trading-date markers and URLs against the supplied evidence bundle. Missing or unknown references produce a visible warning. This audit validates reference membership only; it does not prove that a model's interpretation is correct or independently verify the source's truth.

The evidence package is a research aid, not an autonomous trading signal. The user must inspect original filings and source material before relying on any company or sector hypothesis. The CI suite uses deterministic fakes and does not prove live provider credentials, deployed database connectivity or production data freshness.
