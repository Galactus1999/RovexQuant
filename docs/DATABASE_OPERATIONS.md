# PostgreSQL backup, restore and vault key operations

This runbook applies to the private single-owner Quant pilot. It is an operational
procedure, not proof that the production Neon database has been backed up or
restored. Never paste database URLs, passwords, Fernet keys or provider tokens
into ChatGPT, GitHub issues, CI logs or shell command arguments.

## Storage and migration invariants

- Hosted storage uses PostgreSQL with TLS required.
- **QUANT_TOKEN_DATABASE_URL** is the pooled application connection.
- **QUANT_TOKEN_MIGRATION_DATABASE_URL** is the direct migration/administrative connection.
- Alembic is the only schema migration owner. Run migrations as an explicit deployment operation.
- Startup checks the expected Alembic revision; ordinary requests do not create or alter schema.
- **QUANT_TOKEN_ENCRYPTION_KEY** encrypts provider-token ciphertext. A database backup without the matching key is not a recoverable credential backup.
- Keep the database backup and encryption-key backup in separate, access-controlled encrypted stores.

## Backup procedure

1. Put the API into a maintenance window and stop credential writes and migrations.
2. Confirm the Neon project/branch/database and the intended migration revision through the deployment console. Do not infer production state from CI.
3. Provision a short-lived operator environment from the approved secret manager. Use a protected **PGSERVICEFILE** and **PGPASSFILE** (mode **0600**) so connection credentials are not embedded in command arguments or printed. Configure TLS verification.
4. Create a PostgreSQL custom-format backup with **pg_dump --format=custom --no-owner --no-privileges --file**. Store it in the approved encrypted backup location with restricted access and retention.
5. Record the dump's SHA-256 digest and metadata (database identifier, timestamp, application revision and Alembic revision) without recording credentials or token contents.
6. Preserve the active Fernet key in the separate secrets/key-backup system. Verify that the key identifier/version needed for restoration is recorded, not the key value.
7. Do not resume writes until the backup operation has completed and the maintenance window is cleared.

## Restore and verification procedure

1. Restore the custom-format dump into a new isolated PostgreSQL database using a PostgreSQL client version compatible with the source server. Do not restore over production as the first verification step.
2. Use **pg_restore --exit-on-error --no-owner --no-privileges** and inspect the restored Alembic revision and expected schema.
3. Verify the provider-token table exists and ciphertext is present. Never print or export token values.
4. With the matching historical Fernet key supplied securely to a restricted verification process, verify decryptability without emitting plaintext. A successful database restore alone does not prove the encryption key is available or correct.
5. Run application readiness and vault status checks against the isolated restore. Confirm only safe metadata is returned.
6. Record the restore result, elapsed time and schema revision. Destroy the isolated restore database after verification.
7. For a production recovery, pause writes, restore the verified backup, restore the matching encryption key from the separate key store, deploy the matching application revision, and verify readiness before reopening traffic.

CI runs **backend/scripts/verify_postgres_backup_restore.py** against a disposable
PostgreSQL 16 service and a deterministic test key. It proves the script's
custom-format backup/isolated-restore path and preservation/decryptability of a
test ciphertext. It does **not** prove a production Neon backup, production
restore, PITR configuration or production key custody.

## Provider-vault key rotation

Key rotation is a maintenance operation. The rotation function re-encrypts all
rows in one database transaction; a row that cannot be decrypted aborts the
transaction. The helper script reads keys only from environment variables and
prints only the number of rotated rows.

1. Schedule maintenance and stop all API instances/writers that use the vault. Do not rotate while another process can read/write credentials using the old key.
2. Take and verify a database backup. Confirm that the old key is recoverable from the separate secrets/key-backup system.
3. Generate a new Fernet key using the approved secret manager. Do not print it, pass it as a command-line argument, commit it or place it in CI artifacts.
4. Start a one-off operator process using the same application revision and the restricted database network access. Supply these environment variables through the secret manager only:
   - **QUANT_TOKEN_STORAGE_MODE=postgresql**
   - **QUANT_TOKEN_DATABASE_URL** and **QUANT_TOKEN_MIGRATION_DATABASE_URL**
   - **QUANT_TOKEN_ENCRYPTION_KEY** set to the current old key
   - **QUANT_TOKEN_OLD_ENCRYPTION_KEY** set to the current old key
   - **QUANT_TOKEN_NEW_ENCRYPTION_KEY** set to the new key
5. Run **python scripts/rotate_provider_token_key.py** from the backend working directory. The script verifies that the active key matches the old key, validates storage/schema, then invokes the atomic rotation function. A non-zero exit is a failed rotation; do not change the running API configuration.
6. After a successful commit, update the deployment's **QUANT_TOKEN_ENCRYPTION_KEY** to the new key and restart the API before reopening traffic. Remove the temporary old/new operator variables. Verify readiness and the safe provider-vault status endpoint; never retrieve or display a token.
7. Retain the old key only for the approved backup/restore window. Once a restore using the new key has been verified and retention requirements are satisfied, retire the old key according to the secrets manager's secure-destruction procedure.

If rotation fails before commit, the database transaction is rolled back and the old active key remains valid. If rotation commits but the application cannot start with the new key, keep traffic closed and restore the verified pre-rotation database backup together with the old key. Do not simply switch the application back to the old key against rows already re-encrypted with the new key.

## Operational exclusions

- This runbook does not claim a production Neon backup schedule, point-in-time recovery test, or successful production restore.
- No automatic durable worker, backup scheduler or key-rotation endpoint is exposed by the current Render Free pilot.
- Multi-user key ownership, tenant isolation, distributed rate limiting and public credential self-service remain future-release gates.
