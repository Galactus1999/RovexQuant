# Quant mobile market-data setup

The Android app authenticates to the private Quant backend with a short-lived app session. It never contains the Upstox Analytics Token, admin key, market-gateway key or AI provider keys.

## Required backend configuration

- `QUANT_APP_USERNAME`: private installation login.
- `QUANT_APP_PASSWORD`: long, unique login password.
- `QUANT_APP_SESSION_SIGNING_KEY`: random signing secret of at least 32 characters.
- `QUANT_APP_SESSION_TTL_SECONDS`: optional session lifetime (default 4 hours; maximum 24 hours).
- `QUANT_TOKEN_ENCRYPTION_KEY`: Fernet key used to encrypt provider credentials at rest.
- Hosted PostgreSQL: `QUANT_TOKEN_STORAGE_MODE=postgresql` and `QUANT_TOKEN_DATABASE_URL` (TLS required).
- Local-only SQLite: `QUANT_TOKEN_STORAGE_MODE=sqlite` and `QUANT_TOKEN_DB_PATH` on a persistent local volume.
- `QUANT_AI_GATEWAY_TOKEN`: backend-only AI gateway guard.
- `UPSTOX_ANALYTICS_TOKEN`: optional local-development fallback; encrypted vault credentials take precedence.

Do not use ephemeral filesystem storage for the credential vault. Render Free's ephemeral filesystem is not a durable database.

## Add the Upstox Analytics Token in the app

1. Create a read-only Analytics Token in the Upstox Developer Apps console.
2. Sign in to Quant using the backend HTTPS URL and the private app login.
3. Open **Pulse → Market data connection**.
4. Paste the token into the masked field and select **Save token and connect feed**.
5. Quant sends it over HTTPS to the authenticated app-session endpoint. The backend encrypts it with the configured Fernet key and stores it in the provider vault. The API never returns the token.
6. Quant restarts the market stream. The feed status must progress through upstream authorization and connection to verified received ticks before the UI can report live data.

The app's status endpoint reports whether a token is stored; it deliberately does not call that state "connected". Actual connectivity is established only by the stream. Invalid credentials, entitlement failures and missing tokens remain visible errors.

The legacy admin-only bootstrap endpoint remains available for controlled server administration. Do not put its admin key in the APK.

## What the current stream covers

- The backend fetches the official Upstox NSE BOD instrument master and exposes authenticated index and NSE cash-equity instrument endpoints.
- Android loads the NSE equity universe plus the resolved dashboard index instruments after session restoration/login.
- Quant subscribes in LTPC mode and rejects subscriptions above the documented 5,000-instrument individual LTPC limit; it does not silently truncate the universe.
- Upstox V3 authorization uses `Accept: application/json`. The JSON subscription control message is sent as a WebSocket **binary** frame, and Protobuf market messages are decoded on the backend.
- Each tick carries separate exchange and receive timestamps plus a quality status. No synthetic price is shown when a tick is absent or invalid.

This integration does **not** imply BSE, derivatives, full-depth order book or global-market coverage. The instrument master is reference metadata, not prices. TejHQ EOD data and official NSE MCP data are separate sources and must retain their own EOD/delayed labels and licence restrictions.

## Operational and compliance requirements

- HTTPS/WSS is mandatory outside local development.
- Never place server/provider credentials in the APK, remote config, source control, crash reports, prompts or client logs.
- Back up the PostgreSQL database and Fernet encryption key together. Losing the key makes stored credentials unrecoverable.
- Rotate the app session signing key to revoke active sessions; rotate provider credentials at their provider console if compromised.
- Upstox Analytics Token is read-only market-data access. App login does not prove token validity, entitlement, market-open state or live feed health.
- The current vault and stream are for one private owner installation, not a multi-tenant fan-out service.
- Continuous background ingestion, durable historical quote storage and restart-safe scheduled workers are not yet implemented. A connected WebSocket in an open app is not a substitute for a durable ingestion pipeline.
