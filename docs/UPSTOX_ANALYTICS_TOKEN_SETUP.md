# Quant mobile market-data setup

The Android app authenticates to the Quant backend with a short-lived app session. It never contains the Upstox Analytics Token, the admin key, or the market-gateway key.

## Required server environment

- `QUANT_APP_USERNAME`: login name for the private Quant installation.
- `QUANT_APP_PASSWORD`: long, unique login password (at least 20 random characters recommended).
- `QUANT_APP_SESSION_SIGNING_KEY`: random secret of at least 32 characters; rotate to revoke all active app sessions.
- `QUANT_APP_SESSION_TTL_SECONDS`: optional session lifetime, default 14400 seconds (4 hours; maximum 24 hours).
- `QUANT_TOKEN_ENCRYPTION_KEY`: Fernet key used by the Analytics Token vault.
- `QUANT_TOKEN_DB_PATH`: persistent writable SQLite path.
- `QUANT_ADMIN_GATEWAY_TOKEN`: private admin provisioning key.
- `UPSTOX_ANALYTICS_TOKEN`: optional local-development fallback only; encrypted vault takes precedence.

Generate keys with `python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"` for the Fernet key and `python -c "import secrets; print(secrets.token_urlsafe(48))"` for the session signing key.

## Provision the Upstox Analytics Token

Use the Upstox developer console to create a read-only Analytics Token. Provision it once over HTTPS using the admin key:

```bash
curl --fail-with-body -X POST "$QUANT_API_URL/api/v1/integrations/upstox/analytics-token" \
  -H "X-Quant-Admin-Key: $QUANT_ADMIN_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @<(jq -n --arg token "$UPSTOX_ANALYTICS_TOKEN" '{token:$token}')
```

The API does not return the token. The server stores it encrypted at rest. Keep the Fernet key and SQLite database backup together; losing the key makes the encrypted credential unrecoverable.

## Android connection

1. Deploy the backend behind HTTPS/WSS with a persistent volume.
2. Configure the app login environment variables above.
3. In Quant, enter the HTTPS API base URL, app username and password.
4. The app receives a short-lived bearer session and uses it for protected quote and WebSocket routes.
5. Rotate the signing key to revoke all active sessions.

The V3 WebSocket bridge authorizes with the server-side Analytics Token, subscribes using Upstox's documented JSON control frame, decodes binary Protobuf messages on the backend and forwards normalized JSON ticks to the app. Only NSE index keys are accepted in this initial mobile stream. The bridge allows up to 100 index keys per connection in this implementation; Upstox account-level connection/subscription limits still apply.

## Operational and compliance requirements

- HTTPS/WSS is mandatory outside local development. Do not expose admin endpoints publicly without network controls.
- Apply reverse-proxy login rate limiting, request size limits and access logging with Authorization headers redacted.
- Never put any server secret or provider token in the APK, remote config, source control, crash reports or client logs.
- The SQLite database must be on persistent storage; ephemeral containers will lose credentials and sessions.
- Upstox Analytics Token supports read-only market-data APIs. A successful app login does not prove that the provider credential is provisioned or that the upstream market is open.
- Market data is exchange/provider intellectual property. Verify display, storage and redistribution entitlements before multi-user/public distribution.
- The initial stream is for private single-install use, not a multi-tenant fan-out service. Deploy only one active app stream unless a connection manager/multiplexer is added.
