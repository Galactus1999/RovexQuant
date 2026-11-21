# Upstox Analytics Token setup for Quant

Quant uses the Upstox **Analytics Token** for market-data REST and V3 WebSocket access. It is read-only and is not an order-placement credential.

## Why Analytics Token

Upstox documents this token as free, valid for one year, and supported for Market Quote and WebSocket APIs. The ordinary OAuth access token has a much shorter daily validity and is not the right token lifecycle for this market-data-only service.

Official documentation:
- https://upstox.com/developer/api-documentation/analytics-token/
- https://upstox.com/developer/api-documentation/v3/get-market-data-feed/
- https://upstox.com/developer/api-documentation/get-full-market-quote-v3/

## Server configuration

Set these values in the backend deployment's secret/environment configuration. Never commit actual values:

- `QUANT_ADMIN_GATEWAY_TOKEN`: long random admin credential protecting credential provisioning and status endpoints.
- `QUANT_TOKEN_ENCRYPTION_KEY`: Fernet key generated once and retained securely.
- `QUANT_TOKEN_DB_PATH`: path to a persistent SQLite file on a durable backend volume.
- `QUANT_MARKET_GATEWAY_TOKEN`: legacy server gateway key; do not embed it in the Android APK.
- `UPSTOX_ANALYTICS_TOKEN`: optional local-development fallback only. Prefer the encrypted vault in hosted deployments.

Generate an encryption key on a trusted machine:

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

Back up the encryption key separately from the database. Losing the key makes the encrypted token unrecoverable. Losing the persistent database requires provisioning the Analytics Token again.

## Generate and provision the token

1. Open Upstox Developer Apps and select the Analytics tab.
2. Generate an Analytics Token and copy it once.
3. Send it to the backend over HTTPS using the admin-protected endpoint:

```bash
curl -X POST "https://YOUR_BACKEND/api/v1/integrations/upstox/analytics-token" \
  -H "Content-Type: application/json" \
  -H "X-Quant-Admin-Key: YOUR_QUANT_ADMIN_GATEWAY_TOKEN" \
  --data '{"token":"PASTE_ANALYTICS_TOKEN_HERE"}'
```

4. Check the connection without retrieving the secret:

```bash
curl "https://YOUR_BACKEND/api/v1/integrations/upstox/status" \
  -H "X-Quant-Admin-Key: YOUR_QUANT_ADMIN_GATEWAY_TOKEN"
```

The API never returns the token. It encrypts the value before writing it to SQLite. Configure a persistent disk/volume for `QUANT_TOKEN_DB_PATH`; ephemeral container filesystems are not suitable for credential persistence.

## Security and operating boundaries

- Use HTTPS only. Restrict access to the admin endpoint at the network layer where possible.
- Never place the Analytics Token, encryption key, admin key, or market gateway key in the Android APK, source control, screenshots, issue comments, or logs.
- Rotate/revoke the Analytics Token in Upstox if it is exposed.
- The vault records the documented one-year lifetime and reports expired credentials as disconnected. Re-provision after expiry or revocation.
- Market-data API access does not grant permission to redistribute exchange data to other users. Review Upstox and exchange terms before multi-user distribution.
- The current shared market gateway key is suitable only for a trusted development setup. Android integration must use a separate short-lived app-session authentication layer; never ship that shared key in the client.
