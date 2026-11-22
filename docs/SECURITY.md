# Security

- Never commit broker, market-data or AI keys.
- Android secrets must use Android Keystore-backed encryption; no plaintext preferences, logs, analytics or backups.
- Backend secrets via environment or managed secret store; redact headers and tokens.
- AI keys are optional per provider. Minimize evidence sent and disclose provider routing.
- Broker tokens must be revocable and follow official OAuth/session requirements.
- No order-placement scopes or order endpoints in this project.

## Market-data gateway

- `UPSTOX_ANALYTICS_TOKEN`, `DHAN_ACCESS_TOKEN` and `DHAN_CLIENT_ID` are backend-only credentials. Never place them in the APK, source code, CI logs or issue comments.
- `QUANT_MARKET_GATEWAY_TOKEN` protects the current server-to-server development endpoints. It is not a production mobile authentication system and must never be embedded in the Android client.
- Upstox Analytics Tokens are read-only and long-lived, but remain sensitive account credentials. Do not share a personal token with other Quant users.
- Dhan data APIs have separate eligibility/pricing from its free trading API. Confirm the account's data entitlement before enabling the adapter.
- Provider fallback is only for transient availability failures. Authentication failures and invalid requests fail closed; Quant does not rotate accounts or keys to evade limits.
- Provider adapters return safe status metadata only; credentials and upstream authorization headers are never returned.
- The private owner app can provision its Upstox Analytics Token through the authenticated app-session route. The token is encrypted in the server-side vault; the API returns configuration metadata only. Live connectivity is not claimed until the WebSocket is authorized and verified ticks arrive.
- Before public multi-user access implement per-user JWT/OAuth, tenant-isolated encrypted credential storage, provider account ownership, revocation, budgets, rate limits, audit events and data licensing/redistribution approval.

## Quant AI gateway

- Provider credentials are server-side only. The Android APK must never contain provider keys or the gateway service token.
- The private owner app may submit an AI provider key over HTTPS to the authenticated provider-credential route. The backend encrypts it in the existing credential vault; the key is never returned, logged, stored in Android preferences, or included in prompts. The app-managed credential is preferred for that provider.
- The gateway is disabled unless `QUANT_AI_GATEWAY_TOKEN` is configured. Its current shared-token guard is for controlled server-to-server development; production mobile access requires per-user authentication, authorization and rate limits.
- Configure one default credential with `PROVIDER_API_KEY`, or named credentials as a JSON object in `PROVIDER_API_KEYS_JSON`. Example: `{"primary":"...","research":"..."}`. Credential IDs are selectors, not secrets.
- A named credential is selected explicitly. Quant does not round-robin or rotate keys to bypass provider rate limits, quotas or account entitlements.
- A provider 429, timeout or 5xx can move to a different explicitly configured fallback provider. A 401/403 is treated as a credential/configuration error and does not trigger key rotation.
- Only provider ID, credential ID, model, answer and token usage are returned. API key material and upstream raw error bodies are never returned.
- Prompt content is not logged by this gateway. Requests are limited to 32 messages, 24,000 combined characters and 4,096 output tokens.
- Treat all AI output as untrusted analysis. It cannot override deterministic risk controls, data-quality gates or user authorization.
- The current credential vault is a single-owner/private-pilot store, not tenant-isolated multi-user storage. Before public deployment add per-user JWT/OAuth, tenant-isolated credential encryption, audit events without secrets/prompts, per-user budgets, abuse controls and retention policy.
