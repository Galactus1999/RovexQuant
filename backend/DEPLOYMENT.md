# Quant backend deployment

## Build

From the repository root:

```bash
docker build -t quant-api ./backend
```

The backend uses Neon PostgreSQL for encrypted provider credentials in the hosted
Docker image. The image defaults `QUANT_TOKEN_STORAGE_MODE=postgresql`; startup fails
closed if `QUANT_TOKEN_DATABASE_URL` is missing or invalid. SQLite is permitted only
when `QUANT_TOKEN_STORAGE_MODE=sqlite` is explicitly selected for local development
or isolated tests. Its default path is `./quant-secrets.sqlite3`. Never select SQLite
for Render Free or another ephemeral hosting filesystem.

## Required environment

- `QUANT_APP_USERNAME`, `QUANT_APP_PASSWORD`: private Android app login.
- `QUANT_APP_SESSION_SIGNING_KEY`: at least 32 random characters; rotating it invalidates all current app sessions.
- `QUANT_TOKEN_ENCRYPTION_KEY`: Fernet key for the encrypted Upstox credential vault. Keep this stable across deployments and backups.
- `QUANT_TOKEN_STORAGE_MODE`: set to `postgresql` for hosted deployments; use `sqlite` only for local development and isolated tests.
- `QUANT_TOKEN_DATABASE_URL`: Neon PostgreSQL application connection URL. Use Neon's pooled connection string and keep the password private. The backend enforces TLS.
- `QUANT_TOKEN_MIGRATION_DATABASE_URL`: direct, non-pooled Neon PostgreSQL URL reserved for Alembic migrations. Never use the pooled application URL for schema changes.
- `QUANT_ADMIN_GATEWAY_TOKEN`: private credential for initial Upstox token provisioning.
- `UPSTOX_ANALYTICS_TOKEN`: optional environment fallback; prefer encrypted provisioning.

For local-only SQLite set `QUANT_TOKEN_STORAGE_MODE=sqlite` and optionally
`QUANT_TOKEN_DB_PATH`. The storage mode and URL must agree: PostgreSQL mode without
a URL, or SQLite mode with a PostgreSQL URL, is a startup/configuration error. Token
values remain Fernet-encrypted in either backend. Alembic is the single migration owner. Before deploying a revision that changes
the schema, run `alembic -c alembic.ini upgrade head` from the backend directory
using `QUANT_TOKEN_MIGRATION_DATABASE_URL`. The migration URL must be direct
(non-pooled); the application URL remains pooled. Hosted migrations require
`sslmode=require` or stronger. Runtime requests do not issue PostgreSQL DDL, and
hosted startup refuses a missing or mismatched Alembic schema revision. See
[migrations/README.md](migrations/README.md).
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

The application also applies bounded, process-local rate limits to login, authenticated
research compute and NSE MCP tool calls. This limiter is a single-instance pilot control:
it resets on restart and is not shared across multiple workers/instances. Keep the
reverse-proxy rate limit enabled; before scaling horizontally, replace the in-process
limiter with an atomic shared store and configure trusted proxy CIDRs.

The first version is a private single-install service. Do not run multiple Android streams concurrently against one Upstox account; the bridge does not yet multiplex subscriptions across users.

## Provision the provider token

After deployment, create an Upstox read-only Analytics Token in the Upstox developer console and provision it to the HTTPS endpoint using the procedure in [UPSTOX_ANALYTICS_TOKEN_SETUP.md](UPSTOX_ANALYTICS_TOKEN_SETUP.md). Do not send the token to ChatGPT or put it in the app.

## Smoke-test sequence

1. `GET /api/v1/health` returns `200`.
2. `POST /api/v1/auth/session` with the configured app credentials returns a short-lived bearer token.
3. Authenticated `GET /api/v1/app/market/index-instruments` returns official `NSE_INDEX` keys from the Upstox BOD file.
4. Provision the Analytics Token and verify the admin status endpoint reports connected.
5. Sign in to the Android app with the backend HTTPS URL.
6. Do not accept live streaming as working yet: the backend currently sends the Upstox V3 subscription as a text frame, while the provider requires a binary frame. After the Phase 2 protocol correction, verify during an active NSE session that Nifty 50 / sector ticks, exchange timestamps, receive timestamps and quality labels update. A closed market or missing credential must remain visibly disconnected, never synthesize prices.

CI validates compilation, lint, static/backend tests and APK integrity. PostgreSQL unit tests use a controlled driver double; a real Neon connection and Render deployment must still be verified after the private connection URL is configured. CI cannot prove a real Upstox account token or live exchange stream without the operator's credential and deployed backend.
