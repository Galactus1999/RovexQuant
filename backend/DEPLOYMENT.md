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

### AI provider routing and budgets

- `QUANT_AI_TASK_ROUTES`: optional JSON object keyed by `chat`, `research`, `macro_analysis`, `filing_extraction`, `claim_verification`, or `summarization`. Each route requires `provider_id` and an exact provider-supported `model`; `fallback_providers` is optional and limited to three providers.
- `QUANT_AI_DAILY_TOKEN_BUDGET` and `QUANT_AI_MONTHLY_TOKEN_BUDGET`: hard token ceilings; `0` disables that ceiling. Reservations are enforced against persisted AI research runs.
- `<PROVIDER>_DEFAULT_MODEL` only prefills the Android model field; the app does not discover provider model catalogues. Enter a valid model ID or set the matching default.
- A route's fallback provider must have a credential and `<PROVIDER>_FALLBACK_MODEL` configured. Provider-key presence alone is not a successful connectivity test; use AI Studio's explicit test.

Example shape (replace every model placeholder with an exact ID supported by the selected provider):

```json
{
  "research": {"provider_id": "openrouter", "model": "<exact-model-id>", "fallback_providers": ["groq"]},
  "claim_verification": {"provider_id": "openai", "model": "<independent-verifier-model-id>"}
}
```


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
6. The current source sends the Upstox V3 subscription envelope as a binary frame (covered by the market-stream regression test). The previously deployed `3b2923c` revision sent a text frame and is not protocol-compatible. After deploying the corrected source and provisioning an Analytics Token, verify during an active NSE session that Nifty 50 / sector ticks, exchange timestamps, receive timestamps and quality labels update. A closed market or missing credential must remain visibly disconnected; never synthesize prices.

CI validates compilation, lint, static/backend tests and APK integrity. PostgreSQL unit tests use a controlled driver double; a real Neon connection and Render deployment must still be verified after the private connection URL is configured. CI cannot prove a real Upstox account token or live exchange stream without the operator's credential and deployed backend.


## Backup, restore and encryption-key rotation

These are operator-run procedures. CI proves the process against an isolated
PostgreSQL 16 service; no production Neon backup, restore or key rotation is
performed by the application or this repository.

### Backup

Use the direct Neon connection URL for administrative backup operations. Keep
the URL and backup file outside source control and outside the Android app.

```bash
cd backend
pg_dump --format=custom --no-owner --no-privileges \
  --file "$BACKUP_FILE" "$QUANT_TOKEN_MIGRATION_DATABASE_URL"
```

Store the backup in an access-controlled encrypted backup destination. Keep the
Fernet encryption key in a separate approved secret manager; a database backup
without its matching encryption key is not sufficient to recover credentials.
Define and document the retention window and access owners in the deployment
environment before production use.

### Restore drill

Restore into a new isolated database first; never test restore over the active
database.

```bash
createdb "$RESTORE_DATABASE_NAME"
pg_restore --exit-on-error --no-owner --no-privileges \
  --dbname "$RESTORE_DATABASE_URL" "$BACKUP_FILE"
```

Verify the Alembic revision, expected vault columns, application readiness, and
decryption of a known non-production credential test record. Keep the source
database untouched. After validation, remove the isolated restore database.
The CI integration job runs an equivalent custom-format dump/restore check and
verifies the restored migration revision and encrypted legacy row.

### Fernet key rotation

The rotation utility performs one transaction across all provider credential
rows. If any ciphertext cannot be decrypted with the supplied old key, the
transaction rolls back. It prints only the number of rows rotated.

1. Schedule a maintenance window and stop writes to the credential vault.
2. Take and verify a database backup; preserve the old Fernet key securely.
3. Generate a new Fernet key in the secret manager. Never paste either key into
   chat, logs, source control, CI output or the APK.
4. Run from `backend/` with the database variables configured and both
   `QUANT_TOKEN_OLD_ENCRYPTION_KEY` and `QUANT_TOKEN_NEW_ENCRYPTION_KEY`
   injected by the secret manager:

```bash
uv run --locked python scripts/rotate_provider_token_key.py
```

5. Only after a successful rotation, set `QUANT_TOKEN_ENCRYPTION_KEY` to the
   new key and restart the backend. Validate readiness and the encrypted vault
   status without exposing the token.
6. Retain the old key in the approved recovery store until a subsequent backup
   and restore drill confirms the new key works. Then follow the organization's
   secure retirement procedure.

Rollback requires restoring the matching pre-rotation database backup together
with the old key. Do not point the old key at already-rotated ciphertext.

The production Neon procedure has not been executed in this phase. Treat the
first production restore and rotation as operator-approved change windows.
### Official exchange reconciliation: permission gate

The historical backfill / official reconciliation workflow is operator-initiated; it is not scheduled. Daily EOD collection is handled separately by the TejHQ ingestion workflow.

`download_official_bhavcopy()` denies direct NSE/BSE website downloads by default. Do not set the following repository Variables unless the corresponding exchange has granted express written permission for automated retrieval:

- `NSE_OFFICIAL_AUTOMATED_DATA_PERMISSION=true` and `NSE_OFFICIAL_PERMISSION_REFERENCE=<permission reference>`
- `BSE_OFFICIAL_AUTOMATED_DATA_PERMISSION=true` and `BSE_OFFICIAL_PERMISSION_REFERENCE=<permission reference>`

The permission flags are exchange-specific. A permission for NSE does not authorize BSE retrieval. The workflow and backend both enforce this default-deny check. Without permission, use TejHQ for automatic EOD collection and the official NSE MCP only for on-demand informational queries. An operator-supplied official file reconciliation path is not yet implemented; do not report official reconciliation as complete until a report exists.
