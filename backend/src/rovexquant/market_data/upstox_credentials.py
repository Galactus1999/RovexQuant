"""Encrypted server-side Upstox token storage (PostgreSQL on Render, SQLite locally)."""
from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
import os
from pathlib import Path
import sqlite3
import time
from typing import Any

import psycopg
from cryptography.fernet import Fernet, InvalidToken, MultiFernet

from rovexquant.db.schema import EXPECTED_SCHEMA_REVISION


class UpstoxCredentialError(Exception):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.status_code = status_code


def _fernet() -> Fernet:
    key = os.getenv("QUANT_TOKEN_ENCRYPTION_KEY", "").strip()
    if not key:
        raise UpstoxCredentialError("Token encryption is not configured on the server", 503)
    try:
        return Fernet(key.encode("ascii"))
    except (ValueError, UnicodeEncodeError) as error:
        raise UpstoxCredentialError(
            "QUANT_TOKEN_ENCRYPTION_KEY must be a valid Fernet key", 503
        ) from error


def _database_url() -> str:
    return os.getenv("QUANT_TOKEN_DATABASE_URL", "").strip()


def _storage_mode() -> str:
    """Resolve storage explicitly; Docker/hosted mode defaults to PostgreSQL."""
    database_url = _database_url()
    configured = os.getenv("QUANT_TOKEN_STORAGE_MODE", "").strip().lower()
    mode = configured or ("postgresql" if database_url else "sqlite")
    if mode not in {"postgresql", "sqlite"}:
        raise UpstoxCredentialError("QUANT_TOKEN_STORAGE_MODE must be postgresql or sqlite", 503)
    if mode == "postgresql" and not database_url:
        raise UpstoxCredentialError(
            "PostgreSQL storage is required but QUANT_TOKEN_DATABASE_URL is not configured", 503
        )
    if mode == "postgresql" and not database_url.startswith(("postgresql://", "postgres://")):
        raise UpstoxCredentialError(
            "QUANT_TOKEN_DATABASE_URL must be a PostgreSQL connection URL", 503
        )
    if mode == "sqlite" and database_url:
        raise UpstoxCredentialError(
            "QUANT_TOKEN_DATABASE_URL is set but QUANT_TOKEN_STORAGE_MODE selects SQLite", 503
        )
    return mode


def _database_sslmode() -> str:
    mode = os.getenv("QUANT_TOKEN_DATABASE_SSLMODE", "require").strip().lower()
    allowed = {"require", "verify-ca", "verify-full"}
    if os.getenv("QUANT_ENV") == "test":
        allowed.add("disable")
    if mode not in allowed:
        raise UpstoxCredentialError(
            "QUANT_TOKEN_DATABASE_SSLMODE must enforce TLS outside the test environment", 503
        )
    return mode


def validate_storage_configuration() -> None:
    """Validate hosted storage and encryption configuration without connecting or exposing secrets."""
    mode = _storage_mode()
    if mode == "postgresql":
        _database_sslmode()
        _fernet()


def validate_postgres_schema() -> None:
    """Fail closed when the hosted database has not been migrated to the code's schema head."""
    if _storage_mode() != "postgresql":
        return
    try:
        with psycopg.connect(
            _database_url(), connect_timeout=10, sslmode=_database_sslmode()
        ) as connection:
            row = connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone()
    except psycopg.Error as error:
        raise UpstoxCredentialError(
            "PostgreSQL schema is unavailable or not migrated; apply the Alembic migrations",
            503,
        ) from error
    if not row or row[0] != EXPECTED_SCHEMA_REVISION:
        raise UpstoxCredentialError(
            "PostgreSQL schema revision does not match this application; apply the Alembic migrations",
            503,
        )



def validate_storage_readiness() -> None:
    """Check mandatory credential storage without exposing connection details."""
    validate_storage_configuration()
    validate_postgres_schema()
    if _storage_mode() == "sqlite":
        try:
            with _connect() as connection:
                connection.execute("SELECT 1").fetchone()
        except sqlite3.Error as error:
            raise UpstoxCredentialError("Local credential storage is unavailable", 503) from error


@contextmanager
def _connect() -> Iterator[Any]:
    """Open one short-lived DB connection; never retain a connection across requests."""
    database_url = _database_url()
    if _storage_mode() == "postgresql":
        if not database_url.startswith(("postgresql://", "postgres://")):
            raise UpstoxCredentialError(
                "QUANT_TOKEN_DATABASE_URL must be a PostgreSQL connection URL", 503
            )
        # Neon requires TLS. The pooled endpoint is compatible with short transactions.
        connection = psycopg.connect(
            database_url, connect_timeout=10, sslmode=_database_sslmode()
        )
        try:
            # Schema is owned by Alembic and verified during application startup.
            # Ordinary requests must never issue DDL against the hosted database.
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()
        return

    path = Path(os.getenv("QUANT_TOKEN_DB_PATH", "./quant-secrets.sqlite3"))
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=10)
    try:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute(
            "CREATE TABLE IF NOT EXISTS provider_tokens "
            "(provider TEXT PRIMARY KEY, encrypted_token BLOB NOT NULL, "
            "updated_at INTEGER NOT NULL, expires_at INTEGER)"
        )
        columns = {row[1] for row in connection.execute("PRAGMA table_info(provider_tokens)")}
        if "expires_at" not in columns:
            connection.execute("ALTER TABLE provider_tokens ADD COLUMN expires_at INTEGER")
        connection.commit()
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()



def rotate_provider_token_encryption_key(old_key: str, new_key: str) -> int:
    """Atomically re-encrypt every stored provider token using a new Fernet key.

    The old key remains available only to this operation. If any row cannot be
    decrypted, the transaction rolls back and no partial rotation is committed.
    """
    if not old_key or not new_key or old_key == new_key:
        raise UpstoxCredentialError("Distinct old and new encryption keys are required", 422)
    try:
        old_fernet = Fernet(old_key.encode("ascii"))
        new_fernet = Fernet(new_key.encode("ascii"))
        rotating = MultiFernet([new_fernet, old_fernet])
    except (ValueError, UnicodeEncodeError) as error:
        raise UpstoxCredentialError("Encryption key rotation received an invalid Fernet key", 422) from error

    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    try:
        with _connect() as connection:
            rows = connection.execute(
                "SELECT provider, encrypted_token FROM provider_tokens"
            ).fetchall()
            try:
                updates = [
                    (rotating.rotate(bytes(encrypted_token)), provider)
                    for provider, encrypted_token in rows
                ]
            except InvalidToken as error:
                raise UpstoxCredentialError(
                    "Vault key rotation failed; verify the old key. No changes were committed.",
                    409,
                ) from error
            for encrypted_token, provider in updates:
                connection.execute(
                    f"UPDATE provider_tokens SET encrypted_token={placeholder} WHERE provider={placeholder}",
                    (encrypted_token, provider),
                )
    except (psycopg.Error, sqlite3.Error) as error:
        raise UpstoxCredentialError(
            "Vault key rotation failed because credential storage was unavailable; no changes were committed.",
            503,
        ) from error
    return len(updates)



def store_upstox_analytics_token(token: str) -> None:
    if not isinstance(token, str) or len(token.strip()) < 20 or len(token) > 4096:
        raise UpstoxCredentialError("Invalid Upstox Analytics Token", 422)
    encrypted = _fernet().encrypt(token.strip().encode())
    now = int(time.time())
    # Upstox Analytics Tokens are documented as valid for one year.
    expires_at = now + 365 * 24 * 60 * 60
    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    with _connect() as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at, expires_at) "
            f"VALUES ({placeholder}, {placeholder}, {placeholder}, {placeholder}) "
            "ON CONFLICT(provider) DO UPDATE SET "
            "encrypted_token=excluded.encrypted_token, updated_at=excluded.updated_at, "
            "expires_at=excluded.expires_at",
            ("upstox", encrypted, now, expires_at),
        )



def _ai_provider_storage_key(provider_id: str) -> str:
    # Import lazily to keep the credential-storage module independent at import time.
    from rovexquant.ai.providers import SUPPORTED

    if provider_id not in SUPPORTED:
        raise UpstoxCredentialError("Unsupported AI provider", 422)
    return f"ai:{provider_id}"


def store_ai_provider_api_key(provider_id: str, api_key: str) -> None:
    """Encrypt one owner-managed AI provider key in the shared server-side vault."""
    storage_key = _ai_provider_storage_key(provider_id)
    if not isinstance(api_key, str) or len(api_key.strip()) < 20 or len(api_key) > 4096:
        raise UpstoxCredentialError("Invalid AI provider API key", 422)
    encrypted = _fernet().encrypt(api_key.strip().encode())
    now = int(time.time())
    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    with _connect() as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at, expires_at) "
            f"VALUES ({placeholder}, {placeholder}, {placeholder}, NULL) "
            "ON CONFLICT(provider) DO UPDATE SET "
            "encrypted_token=excluded.encrypted_token, updated_at=excluded.updated_at, "
            "expires_at=NULL",
            (storage_key, encrypted, now),
        )


def get_ai_provider_api_key(provider_id: str) -> str | None:
    try:
        storage_key = _ai_provider_storage_key(provider_id)
        placeholder = "%s" if _storage_mode() == "postgresql" else "?"
        with _connect() as connection:
            row = connection.execute(
                "SELECT encrypted_token FROM provider_tokens "
                f"WHERE provider={placeholder}",
                (storage_key,),
            ).fetchone()
        if not row:
            return None
        return _fernet().decrypt(bytes(row[0])).decode()
    except (sqlite3.Error, psycopg.Error, InvalidToken, UnicodeDecodeError,
            UpstoxCredentialError, ValueError):
        return None


def delete_ai_provider_api_key(provider_id: str) -> bool:
    storage_key = _ai_provider_storage_key(provider_id)
    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    with _connect() as connection:
        cursor = connection.execute(
            f"DELETE FROM provider_tokens WHERE provider={placeholder}",
            (storage_key,),
        )
    return bool(getattr(cursor, "rowcount", 0))


def delete_upstox_analytics_token() -> bool:
    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    with _connect() as connection:
        cursor = connection.execute(
            f"DELETE FROM provider_tokens WHERE provider={placeholder}",
            ("upstox",),
        )
    return bool(getattr(cursor, "rowcount", 0))


def get_upstox_analytics_token() -> str | None:
    placeholder = "%s" if _database_url() else "?"
    try:
        with _connect() as connection:
            row = connection.execute(
                "SELECT encrypted_token, expires_at FROM provider_tokens "
                f"WHERE provider={placeholder}",
                ("upstox",),
            ).fetchone()
        if not row or row[1] is None or int(row[1]) <= int(time.time()):
            return None
        return _fernet().decrypt(bytes(row[0])).decode()
    except (sqlite3.Error, psycopg.Error, InvalidToken, UnicodeDecodeError,
            UpstoxCredentialError, ValueError):
        return None


def upstox_analytics_status() -> dict[str, object]:
    placeholder = "%s" if _database_url() else "?"
    try:
        with _connect() as connection:
            row = connection.execute(
                "SELECT updated_at, expires_at FROM provider_tokens "
                f"WHERE provider={placeholder}",
                ("upstox",),
            ).fetchone()
        now = int(time.time())
        connected = bool(row and row[1] is not None and int(row[1]) > now
                         and get_upstox_analytics_token())
        return {
            "provider_id": "upstox",
            "connected": connected,
            "credential_type": "analytics_read_only",
            "updated_at": row[0] if row else None,
            "expires_at": row[1] if row else None,
            "credential_storage": "encrypted_server_side" if connected else "not_connected",
        }
    except (sqlite3.Error, psycopg.Error, ValueError, UpstoxCredentialError):
        return {"provider_id": "upstox", "connected": False,
                "credential_type": "analytics_read_only", "credential_storage": "unavailable"}


_RESEARCH_CONNECTOR_IDS = {"dalalos"}


def _research_connector_storage_key(connector_id: str) -> str:
    if connector_id not in _RESEARCH_CONNECTOR_IDS:
        raise UpstoxCredentialError("Unsupported research connector", 422)
    return f"mcp:{connector_id}"


def store_research_connector_api_key(connector_id: str, api_key: str) -> None:
    """Encrypt an owner-managed external MCP key in the existing provider-token vault."""
    storage_key = _research_connector_storage_key(connector_id)
    if (
        not isinstance(api_key, str)
        or len(api_key.strip()) < 20
        or len(api_key) > 4096
        or not api_key.strip().isascii()
        or any(ord(character) < 33 or ord(character) > 126 for character in api_key.strip())
    ):
        raise UpstoxCredentialError("Invalid research connector API key", 422)
    encrypted = _fernet().encrypt(api_key.strip().encode("ascii"))
    now = int(time.time())
    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    with _connect() as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at, expires_at) "
            f"VALUES ({placeholder}, {placeholder}, {placeholder}, NULL) "
            "ON CONFLICT(provider) DO UPDATE SET "
            "encrypted_token=excluded.encrypted_token, updated_at=excluded.updated_at, "
            "expires_at=NULL",
            (storage_key, encrypted, now),
        )


def get_research_connector_api_key(connector_id: str) -> str | None:
    try:
        storage_key = _research_connector_storage_key(connector_id)
        placeholder = "%s" if _storage_mode() == "postgresql" else "?"
        with _connect() as connection:
            row = connection.execute(
                "SELECT encrypted_token FROM provider_tokens "
                f"WHERE provider={placeholder}",
                (storage_key,),
            ).fetchone()
        if not row:
            return None
        return _fernet().decrypt(bytes(row[0])).decode()
    except (sqlite3.Error, psycopg.Error, InvalidToken, UnicodeDecodeError,
            UpstoxCredentialError, ValueError):
        return None


def delete_research_connector_api_key(connector_id: str) -> bool:
    storage_key = _research_connector_storage_key(connector_id)
    placeholder = "%s" if _storage_mode() == "postgresql" else "?"
    with _connect() as connection:
        cursor = connection.execute(
            f"DELETE FROM provider_tokens WHERE provider={placeholder}",
            (storage_key,),
        )
    return bool(getattr(cursor, "rowcount", 0))
