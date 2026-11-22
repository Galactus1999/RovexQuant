from __future__ import annotations

import os

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from cryptography.fernet import Fernet, InvalidToken

from rovexquant.db.schema import EXPECTED_SCHEMA_REVISION
from rovexquant.market_data import upstox_credentials as vault

pytestmark = pytest.mark.integration
TEST_VAULT_KEY = Fernet.generate_key()
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
            "VALUES (%s, %s, %s) ON CONFLICT(provider) DO NOTHING",
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
