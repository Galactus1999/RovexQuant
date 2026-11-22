from __future__ import annotations

import os

import psycopg
import pytest
from alembic import command
from alembic.config import Config
from cryptography.fernet import Fernet

from rovexquant.db.schema import EXPECTED_SCHEMA_REVISION
from rovexquant.market_data import upstox_credentials as vault

pytestmark = pytest.mark.integration


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
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())

    config = Config("alembic.ini")
    command.upgrade(config, "head")
    command.upgrade(config, "head")  # A second upgrade must be a no-op.

    yield database_url

    with psycopg.connect(database_url, sslmode="disable") as connection:
        connection.execute("DELETE FROM provider_tokens WHERE provider = %s", ("upstox",))


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

    with pytest.raises(vault.UpstoxCredentialError, match="revision does not match"):
        vault.validate_postgres_schema()

    with psycopg.connect(migrated_postgres, sslmode="disable") as connection:
        connection.execute(
            "UPDATE alembic_version SET version_num = %s",
            (EXPECTED_SCHEMA_REVISION,),
        )
