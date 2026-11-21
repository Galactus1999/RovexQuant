# Security

- Never commit broker, market-data or AI keys.
- Android secrets must use Android Keystore-backed encryption; no plaintext preferences, logs, analytics or backups.
- Backend secrets via environment or managed secret store; redact headers and tokens.
- AI keys are optional per provider. Minimize evidence sent and disclose provider routing.
- Broker tokens must be revocable and follow official OAuth/session requirements.
- No order-placement scopes or order endpoints in this project.

## Quant AI gateway

- Provider credentials are server-side only. The Android APK must never contain provider keys or the gateway service token.
- The gateway is disabled unless `QUANT_AI_GATEWAY_TOKEN` is configured. Its current shared-token guard is for controlled server-to-server development; production mobile access requires per-user authentication, authorization and rate limits.
- Configure one default credential with `PROVIDER_API_KEY`, or named credentials as a JSON object in `PROVIDER_API_KEYS_JSON`. Example: `{"primary":"...","research":"..."}`. Credential IDs are selectors, not secrets.
- A named credential is selected explicitly. Quant does not round-robin or rotate keys to bypass provider rate limits, quotas or account entitlements.
- A provider 429, timeout or 5xx can move to a different explicitly configured fallback provider. A 401/403 is treated as a credential/configuration error and does not trigger key rotation.
- Only provider ID, credential ID, model, answer and token usage are returned. API key material and upstream raw error bodies are never returned.
- Prompt content is not logged by this gateway. Requests are limited to 32 messages, 24,000 combined characters and 4,096 output tokens.
- Treat all AI output as untrusted analysis. It cannot override deterministic risk controls, data-quality gates or user authorization.
- Before public deployment add per-user JWT/OAuth, tenant-isolated credential encryption, audit events without secrets/prompts, per-user budgets, abuse controls and retention policy.
