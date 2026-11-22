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
from cryptography.fernet import Fernet, InvalidToken


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


@contextmanager
def _connect() -> Iterator[Any]:
    """Open one short-lived DB connection; never retain a connection across requests."""
    database_url = _database_url()
    if database_url:
        if not database_url.startswith(("postgresql://", "postgres://")):
            raise UpstoxCredentialError(
                "QUANT_TOKEN_DATABASE_URL must be a PostgreSQL connection URL", 503
            )
        # Neon requires TLS. The pooled endpoint is compatible with short transactions.
        connection = psycopg.connect(database_url, connect_timeout=10, sslmode="require")
        try:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS provider_tokens "
                "(provider TEXT PRIMARY KEY, encrypted_token BYTEA NOT NULL, "
                "updated_at BIGINT NOT NULL, expires_at BIGINT)"
            )
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


def store_upstox_analytics_token(token: str) -> None:
    if not isinstance(token, str) or len(token.strip()) < 20 or len(token) > 4096:
        raise UpstoxCredentialError("Invalid Upstox Analytics Token", 422)
    encrypted = _fernet().encrypt(token.strip().encode())
    now = int(time.time())
    # Upstox Analytics Tokens are documented as valid for one year.
    expires_at = now + 365 * 24 * 60 * 60
    placeholder = "%s" if _database_url() else "?"
    with _connect() as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at, expires_at) "
            f"VALUES ({placeholder}, {placeholder}, {placeholder}, {placeholder}) "
            "ON CONFLICT(provider) DO UPDATE SET "
            "encrypted_token=excluded.encrypted_token, updated_at=excluded.updated_at, "
            "expires_at=excluded.expires_at",
            ("upstox", encrypted, now, expires_at),
        )


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
