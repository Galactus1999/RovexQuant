from __future__ import annotations

import base64
import hashlib
import os

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from cryptography.fernet import Fernet, InvalidToken

from rovexquant.db.schema import EXPECTED_SCHEMA_REVISION
from rovexquant.market_data import upstox_credentials as vault

pytestmark = pytest.mark.integration
TEST_VAULT_KEY = base64.urlsafe_b64encode(
    hashlib.sha256(b"RovexQuant Phase 1 PostgreSQL integration test key only").digest()
)
LEGACY_CIPHERTEXT = Fernet(TEST_VAULT_KEY).encrypt(b"legacy-ciphertext-preservation-probe")


@pytest.fixture
def migrated_postgres(monkeypatch):
    database_url = os.getenv("QUANT_TOKEN_TEST_DATABASE_URL", "").strip()
    if not database_url:
        pytest.skip("QUANT_TOKEN_TEST_DATABASE_URL is not configured")

    monkeypatch.setenv("QUANT_ENV", "test")
    monkeypatch.setenv("QUANT_TOKEN_DATABASE_SSLMODE", "disable")
    monkeypatch.setenv("QUANT_TOKEN_MIGRATION_DATABASE_URL", database_url)
    monkeypatch.setenv("QUANT_TOKEN_DATABASE_URL", database_url)
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", TEST_VAULT_KEY.decode())

    # Simulate a pre-migration install. The baseline must adopt this table and
    # preserve its existing encrypted bytes rather than recreate or truncate it.
    with psycopg.connect(database_url, sslmode="disable") as connection:
        connection.execute(
            "CREATE TABLE IF NOT EXISTS provider_tokens "
            "(provider TEXT PRIMARY KEY, encrypted_token BYTEA NOT NULL, updated_at BIGINT NOT NULL)"
        )
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at) "
            "VALUES (%s, %s, %s) ON CONFLICT(provider) DO UPDATE "
            "SET encrypted_token=EXCLUDED.encrypted_token, updated_at=EXCLUDED.updated_at",
            ("phase1-legacy-migration-probe", LEGACY_CIPHERTEXT, 1),
        )

    config = Config("alembic.ini")
    command.upgrade(config, "head")
    command.upgrade(config, "head")  # A second upgrade must be a no-op.

    yield database_url

    with psycopg.connect(database_url, sslmode="disable") as connection:
        connection.execute(
            "DELETE FROM provider_tokens WHERE provider IN (%s, %s)",
            ("upstox", "phase1-rollback-probe"),
        )
        connection.execute(
            "UPDATE provider_tokens SET encrypted_token=%s, updated_at=%s, expires_at=NULL "
            "WHERE provider=%s",
            (LEGACY_CIPHERTEXT, 1, "phase1-legacy-migration-probe"),
        )


def test_postgres_migration_creates_and_records_expected_schema(migrated_postgres):
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        revision = connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchone()
        columns = {
            row[0]
            for row in connection.execute(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'provider_tokens'
                """
            ).fetchall()
        }

    assert revision == (EXPECTED_SCHEMA_REVISION,)
    assert columns == {"provider", "encrypted_token", "updated_at", "expires_at"}


def test_postgres_vault_round_trip_is_encrypted_and_schema_verified(migrated_postgres):
    token = "postgres-integration-token-123456789"

    vault.validate_storage_configuration()
    vault.validate_postgres_schema()
    vault.store_upstox_analytics_token(token)

    assert vault.get_upstox_analytics_token() == token
    assert vault.upstox_analytics_status()["connected"] is True

    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        stored = connection.execute(
            "SELECT encrypted_token FROM provider_tokens WHERE provider = %s",
            ("upstox",),
        ).fetchone()[0]

    assert token.encode() not in bytes(stored)


def test_postgres_vault_replaces_and_expires_credentials(migrated_postgres):
    first = "postgres-first-token-123456789"
    second = "postgres-second-token-123456789"
    vault.store_upstox_analytics_token(first)
    vault.store_upstox_analytics_token(second)

    assert vault.get_upstox_analytics_token() == second

    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        connection.execute(
            "UPDATE provider_tokens SET expires_at = 1 WHERE provider = %s",
            ("upstox",),
        )

    assert vault.get_upstox_analytics_token() is None
    assert vault.upstox_analytics_status()["connected"] is False



def test_postgres_startup_rejects_schema_revision_mismatch(migrated_postgres):
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        connection.execute(
            "UPDATE alembic_version SET version_num = %s",
            ("unknown_revision",),
        )

    try:
        with pytest.raises(vault.UpstoxCredentialError, match="revision does not match"):
            vault.validate_postgres_schema()
    finally:
        with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
            connection.execute(
                "UPDATE alembic_version SET version_num = %s",
                (EXPECTED_SCHEMA_REVISION,),
            )

def test_postgres_baseline_adopts_legacy_vault_without_rewriting_ciphertext(migrated_postgres):
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        row = connection.execute(
            "SELECT encrypted_token, expires_at FROM provider_tokens WHERE provider=%s",
            ("phase1-legacy-migration-probe",),
        ).fetchone()
    assert row == (LEGACY_CIPHERTEXT, None)
    assert Fernet(TEST_VAULT_KEY).decrypt(bytes(row[0])) == b"legacy-ciphertext-preservation-probe"


def test_postgres_vault_transaction_rolls_back_partial_changes(migrated_postgres):
    with pytest.raises(RuntimeError, match="force transaction rollback"):
        with vault._connect() as connection:
            connection.execute(
                "INSERT INTO provider_tokens(provider, encrypted_token, updated_at, expires_at) "
                "VALUES (%s, %s, %s, %s)",
                ("phase1-rollback-probe", b"must-not-commit", 1, None),
            )
            raise RuntimeError("force transaction rollback")
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        row = connection.execute(
            "SELECT provider FROM provider_tokens WHERE provider=%s",
            ("phase1-rollback-probe",),
        ).fetchone()
    assert row is None


def test_postgres_vault_key_rotation_is_atomic_and_new_key_decrypts(migrated_postgres, monkeypatch):
    old_key = TEST_VAULT_KEY
    new_key = Fernet.generate_key()
    token = "postgres-key-rotation-token-123456789"
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", old_key.decode())
    vault.store_upstox_analytics_token(token)

    assert vault.rotate_provider_token_encryption_key(
        old_key.decode(), new_key.decode()
    ) >= 1

    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", new_key.decode())
    assert vault.get_upstox_analytics_token() == token
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        encrypted = connection.execute(
            "SELECT encrypted_token FROM provider_tokens WHERE provider=%s",
            ("upstox",),
        ).fetchone()[0]
    assert Fernet(new_key).decrypt(bytes(encrypted)).decode() == token
    with pytest.raises(InvalidToken):
        Fernet(old_key).decrypt(bytes(encrypted))


def test_postgres_vault_key_rotation_wrong_old_key_rolls_back(migrated_postgres, monkeypatch):
    token = "postgres-rotation-rollback-token-123456"
    wrong_old_key = Fernet.generate_key()
    new_key = Fernet.generate_key()
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", TEST_VAULT_KEY.decode())
    vault.store_upstox_analytics_token(token)

    with pytest.raises(vault.UpstoxCredentialError, match="verify the old key"):
        vault.rotate_provider_token_encryption_key(
            wrong_old_key.decode(), new_key.decode()
        )

    assert vault.get_upstox_analytics_token() == token


def test_postgres_market_eod_migration_creates_provenance_tables(migrated_postgres):
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        tables = {
            row[0] for row in connection.execute(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('market_eod_bars', 'market_ingestion_runs', 'market_ingestion_jobs', 'market_historical_backfill_runs', 'market_historical_backfill_days', 'market_reconciliation_reports', 'market_reconciliation_diffs', 'market_eod_adjusted_bars', 'market_corporate_actions', 'market_symbol_history', 'market_derived_backfill_runs', 'macro_observations', 'macro_ingestion_runs', 'intelligence_ingestion_runs', 'intelligence_events', 'intelligence_event_sources', 'intelligence_issuer_aliases', 'intelligence_event_issuers')
                """
            ).fetchall()
        }
        bar_columns = {
            row[0] for row in connection.execute(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'market_eod_bars'
                """
            ).fetchall()
        }
    assert tables == {"market_eod_bars", "market_ingestion_runs", "market_ingestion_jobs", "market_historical_backfill_runs", "market_historical_backfill_days", "market_reconciliation_reports", "market_reconciliation_diffs", "market_eod_adjusted_bars", "market_corporate_actions", "market_symbol_history", "market_derived_backfill_runs", "macro_observations", "macro_ingestion_runs", "intelligence_ingestion_runs", "intelligence_events", "intelligence_event_sources", "intelligence_issuer_aliases", "intelligence_event_issuers"}
    assert {
        "exchange", "trading_date", "symbol", "series", "open", "high",
        "low", "close", "volume", "isin", "trades", "provider", "data_mode",
        "quality_status", "source_retrieved_at",
    }.issubset(bar_columns)


def test_postgres_market_ingestion_queue_has_lease_and_retry_contract(migrated_postgres):
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        columns = {
            row[0] for row in connection.execute(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'market_ingestion_jobs'
                """
            ).fetchall()
        }
        indexes = {
            row[0] for row in connection.execute(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'market_ingestion_jobs'
                """
            ).fetchall()
        }
    assert {"run_id", "status", "attempt_count", "available_at", "lease_owner",
            "lease_expires_at", "heartbeat_at", "last_error"}.issubset(columns)
    assert {"ix_market_ingestion_jobs_claim", "ix_market_ingestion_jobs_lease"}.issubset(indexes)


def test_postgres_ingestion_worker_recovers_expired_lease_and_reconciles_commit(migrated_postgres):
    from uuid import uuid4
    from rovexquant.market_data.eod_ingestion import claim_next_ingestion_job

    run_id = uuid4()
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        connection.execute(
            """
            INSERT INTO market_ingestion_runs
                (run_id, provider, exchange, status, started_at)
            VALUES (%s, 'tejhq_eod_test', 'NSE', 'RUNNING', NOW())
            """,
            (run_id,),
        )
        connection.execute(
            """
            INSERT INTO market_ingestion_jobs (run_id, exchange, status)
            VALUES (%s, 'NSE', 'QUEUED')
            """,
            (run_id,),
        )

    first = claim_next_ingestion_job("worker-a")
    assert first == {"run_id": str(run_id), "exchange": "NSE"}
    assert claim_next_ingestion_job("worker-b") is None

    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        connection.execute(
            "UPDATE market_ingestion_jobs SET lease_expires_at=NOW() - INTERVAL '1 second' WHERE run_id=%s",
            (run_id,),
        )

    recovered = claim_next_ingestion_job("worker-b")
    assert recovered == first

    # Simulate the worker dying after the EOD transaction commits but before
    # its queue acknowledgement; the next poll must reconcile, not re-run.
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        connection.execute(
            "UPDATE market_ingestion_runs SET status='SUCCEEDED', completed_at=NOW() WHERE run_id=%s",
            (run_id,),
        )

    assert claim_next_ingestion_job("worker-c") is None
    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        job = connection.execute(
            "SELECT status, attempt_count, lease_owner FROM market_ingestion_jobs WHERE run_id=%s",
            (run_id,),
        ).fetchone()
        connection.execute("DELETE FROM market_ingestion_runs WHERE run_id=%s", (run_id,))

    assert job == ("SUCCEEDED", 2, None)
