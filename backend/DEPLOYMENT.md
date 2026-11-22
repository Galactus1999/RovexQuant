# Quant backend deployment

## Build

From the repository root:

```bash
docker build -t quant-api ./backend
```

The backend uses Neon PostgreSQL for encrypted provider credentials when
`QUANT_TOKEN_DATABASE_URL` is configured. Without that variable, local development
falls back to SQLite at `QUANT_TOKEN_DB_PATH` (default:
`./quant-secrets.sqlite3`). Do not rely on SQLite on an ephemeral hosting filesystem.

## Required environment

- `QUANT_APP_USERNAME`, `QUANT_APP_PASSWORD`: private Android app login.
- `QUANT_APP_SESSION_SIGNING_KEY`: at least 32 random characters; rotating it invalidates all current app sessions.
- `QUANT_TOKEN_ENCRYPTION_KEY`: Fernet key for the encrypted Upstox credential vault. Keep this stable across deployments and backups.
- `QUANT_TOKEN_DATABASE_URL`: Neon PostgreSQL connection URL. Use Neon's pooled connection string and keep the password private. The backend enforces TLS.
- `QUANT_ADMIN_GATEWAY_TOKEN`: private credential for initial Upstox token provisioning.
- `UPSTOX_ANALYTICS_TOKEN`: optional environment fallback; prefer encrypted provisioning.

For local-only SQLite use `QUANT_TOKEN_DB_PATH`; it is ignored when
`QUANT_TOKEN_DATABASE_URL` is set. PostgreSQL table creation is idempotent and runs
when the vault first connects. Token values remain Fernet-encrypted in either backend.
The application opens short-lived database connections and does not keep a connection
pool alive across requests.

Generate secrets outside source control. Never commit a populated `.env`, pass secrets as
Docker build arguments, or put them in the APK. Configure the Neon URL only in the
Render service's private environment settings.

## Reverse proxy and transport

Expose the API only through a managed HTTPS endpoint with WebSocket upgrade support (WSS). The Android app rejects non-HTTPS base URLs. Configure the reverse proxy to:

- terminate TLS and redirect HTTP to HTTPS;
- support WebSocket upgrades for `/api/v1/app/market/stream`;
- rate-limit `POST /api/v1/auth/session`;
- redact `Authorization`, `X-Quant-Admin-Key` and request bodies from logs;
- restrict admin credential provisioning to an operator-controlled network or private tunnel;
- enforce request/body limits and sensible connection idle timeouts.

The first version is a private single-install service. Do not run multiple Android streams concurrently against one Upstox account; the bridge does not yet multiplex subscriptions across users.

## Provision the provider token

After deployment, create an Upstox read-only Analytics Token in the Upstox developer console and provision it to the HTTPS endpoint using the procedure in [UPSTOX_ANALYTICS_TOKEN_SETUP.md](UPSTOX_ANALYTICS_TOKEN_SETUP.md). Do not send the token to ChatGPT or put it in the app.

## Smoke-test sequence

1. `GET /api/v1/health` returns `200`.
2. `POST /api/v1/auth/session` with the configured app credentials returns a short-lived bearer token.
3. Authenticated `GET /api/v1/app/market/index-instruments` returns official `NSE_INDEX` keys from the Upstox BOD file.
4. Provision the Analytics Token and verify the admin status endpoint reports connected.
5. Sign in to the Android app with the backend HTTPS URL.
6. During an active NSE session, confirm Nifty 50 / sector ticks, exchange timestamps, receive timestamps and quality labels update. A closed market or missing credential must remain visibly disconnected, never synthesize prices.

CI validates compilation, lint, static/backend tests and APK integrity. PostgreSQL unit tests use a controlled driver double; a real Neon connection and Render deployment must still be verified after the private connection URL is configured. CI cannot prove a real Upstox account token or live exchange stream without the operator's credential and deployed backend.
