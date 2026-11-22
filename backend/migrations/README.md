# Database migrations

Alembic is the single schema-migration owner. Runtime API requests never run DDL.

- Application traffic: `QUANT_TOKEN_DATABASE_URL` (Neon pooled endpoint).
- Migrations: `QUANT_TOKEN_MIGRATION_DATABASE_URL` (Neon direct endpoint; hostname must not contain `-pooler.`).
- Hosted migrations require TLS through `sslmode=require` or stronger.
- Run from the `backend/` directory: `alembic -c alembic.ini upgrade head`.
- Apply migrations as an explicit deployment operation before rolling out code that depends on the new schema.
- Migrations are forward-only when reversal could destroy credentials. Correct mistakes with a new reviewed migration.
- Never print, commit or paste either connection URL. Never use the pooled URL for migrations.

The initial migration adopts the existing `provider_tokens` table without dropping data and adds the expiry column if absent. Existing deployments should run the baseline migration once; it creates Alembic's version table and records the schema revision.
