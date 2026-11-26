import sqlite3

from cryptography.fernet import Fernet

from rovexquant.market_data import upstox_credentials as vault


def configure_local_vault(monkeypatch, tmp_path):
    monkeypatch.delenv("QUANT_TOKEN_DATABASE_URL", raising=False)
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "sqlite")
    monkeypatch.setenv("QUANT_TOKEN_DB_PATH", str(tmp_path / "vault.sqlite3"))
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())


def test_sqlite_vault_encrypts_and_round_trips_token(monkeypatch, tmp_path):
    configure_local_vault(monkeypatch, tmp_path)
    token = "upstox-analytics-token-value-123456"

    vault.store_upstox_analytics_token(token)

    assert vault.get_upstox_analytics_token() == token
    assert vault.upstox_analytics_status()["connected"] is True
    raw = (tmp_path / "vault.sqlite3").read_bytes()
    assert token.encode() not in raw



def test_sqlite_vault_purges_retired_dalalos_key_and_preserves_active_keys(monkeypatch, tmp_path):
    configure_local_vault(monkeypatch, tmp_path)
    upstox_token = "upstox-analytics-token-value-123456"
    ai_key = "provider-api-key-secret-value-123456789"
    vault.store_upstox_analytics_token(upstox_token)
    vault.store_ai_provider_api_key("groq", ai_key)

    with sqlite3.connect(tmp_path / "vault.sqlite3") as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at, expires_at) "
            "VALUES (?, ?, ?, NULL)",
            ("mcp:dalalos", b"legacy-encrypted-dalalos-key", 1),
        )

    # The next ordinary vault access runs the idempotent legacy-key cleanup.
    assert vault.get_upstox_analytics_token() == upstox_token
    assert vault.get_ai_provider_api_key("groq") == ai_key
    with sqlite3.connect(tmp_path / "vault.sqlite3") as connection:
        providers = {
            row[0] for row in connection.execute(
                "SELECT provider FROM provider_tokens"
            ).fetchall()
        }
    assert providers == {"upstox", "ai:groq"}


def test_sqlite_vault_replaces_existing_token(monkeypatch, tmp_path):
    configure_local_vault(monkeypatch, tmp_path)
    vault.store_upstox_analytics_token("first-token-value-123456789")
    vault.store_upstox_analytics_token("second-token-value-123456789")

    assert vault.get_upstox_analytics_token() == "second-token-value-123456789"


def test_sqlite_vault_does_not_return_expired_token(monkeypatch, tmp_path):
    configure_local_vault(monkeypatch, tmp_path)
    vault.store_upstox_analytics_token("expired-token-value-123456789")
    with sqlite3.connect(tmp_path / "vault.sqlite3") as connection:
        connection.execute("UPDATE provider_tokens SET expires_at=1 WHERE provider='upstox'")

    assert vault.get_upstox_analytics_token() is None
    assert vault.upstox_analytics_status()["connected"] is False


def test_postgres_backend_uses_tls_and_bytea(monkeypatch):
    token = "postgres-test-token-value-123456789"
    key = Fernet.generate_key().decode()
    calls = []

    class Cursor:
        def __init__(self, row=None):
            self.row = row

        def fetchone(self):
            return self.row

    class FakeConnection:
        def __init__(self):
            self.stored = None
            self.ddl = []

        def execute(self, sql, params=None):
            self.ddl.append(sql)
            if sql.startswith("INSERT INTO provider_tokens"):
                self.stored = params[1]
                return Cursor()
            if sql.startswith("SELECT encrypted_token"):
                return Cursor((self.stored, 4102444800))
            if sql.startswith("SELECT updated_at"):
                return Cursor((1700000000, 4102444800))
            return Cursor()

        def commit(self):
            pass

        def rollback(self):
            pass

        def close(self):
            pass

    connection = FakeConnection()

    def fake_connect(url, **kwargs):
        calls.append((url, kwargs))
        return connection

    monkeypatch.setenv(
        "QUANT_TOKEN_DATABASE_URL",
        "postgresql://quant:private@ep-example-pooler.ap-southeast-1.aws.neon.tech/neondb",
    )
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", key)
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.setattr(vault.psycopg, "connect", fake_connect)

    vault.store_upstox_analytics_token(token)
    assert vault.get_upstox_analytics_token() == token
    assert vault.upstox_analytics_status()["connected"] is True
    assert calls and calls[0][1]["sslmode"] == "require"
    assert not any(sql.startswith("CREATE TABLE") for sql in connection.ddl)
    assert token.encode() not in connection.stored



def test_hosted_postgres_mode_fails_closed_without_database_url(monkeypatch):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.delenv("QUANT_TOKEN_DATABASE_URL", raising=False)

    try:
        vault.validate_storage_configuration()
    except vault.UpstoxCredentialError as error:
        assert error.status_code == 503
        assert "QUANT_TOKEN_DATABASE_URL" in str(error)
    else:
        raise AssertionError("PostgreSQL mode must not silently fall back to SQLite")


def test_sqlite_mode_rejects_conflicting_postgres_url(monkeypatch):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "sqlite")
    monkeypatch.setenv(
        "QUANT_TOKEN_DATABASE_URL",
        "postgresql://quant:private@ep-example-pooler.ap-southeast-1.aws.neon.tech/neondb",
    )

    try:
        vault.validate_storage_configuration()
    except vault.UpstoxCredentialError as error:
        assert error.status_code == 503
        assert "selects SQLite" in str(error)
    else:
        raise AssertionError("Conflicting SQLite/PostgreSQL configuration must fail closed")



def test_database_tls_cannot_be_disabled_outside_test_environment(monkeypatch):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.setenv(
        "QUANT_TOKEN_DATABASE_URL",
        "postgresql://quant:private@ep-example.ap-southeast-1.aws.neon.tech/neondb?sslmode=disable",
    )
    monkeypatch.setenv("QUANT_TOKEN_DATABASE_SSLMODE", "disable")
    monkeypatch.delenv("QUANT_ENV", raising=False)

    try:
        vault.validate_storage_configuration()
    except vault.UpstoxCredentialError as error:
        assert error.status_code == 503
        assert "enforce TLS" in str(error)
    else:
        raise AssertionError("TLS must not be disabled outside the test environment")


def test_test_environment_can_use_local_postgres_without_tls(monkeypatch):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.setenv(
        "QUANT_TOKEN_DATABASE_URL",
        "postgresql://quant:private@localhost:5432/quant_test?sslmode=disable",
    )
    monkeypatch.setenv("QUANT_TOKEN_DATABASE_SSLMODE", "disable")
    monkeypatch.setenv("QUANT_ENV", "test")
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())

    vault.validate_storage_configuration()


def test_ai_provider_keys_are_encrypted_replaceable_and_deletable(monkeypatch, tmp_path):
    configure_local_vault(monkeypatch, tmp_path)
    key = "provider-api-key-secret-value-123456789"

    vault.store_ai_provider_api_key("groq", key)

    assert vault.get_ai_provider_api_key("groq") == key
    raw = (tmp_path / "vault.sqlite3").read_bytes()
    assert key.encode() not in raw
    assert vault.delete_ai_provider_api_key("groq") is True
    assert vault.get_ai_provider_api_key("groq") is None
    assert vault.delete_ai_provider_api_key("groq") is False


def test_ai_provider_vault_rejects_unsupported_provider_and_short_key(monkeypatch, tmp_path):
    configure_local_vault(monkeypatch, tmp_path)
    try:
        vault.store_ai_provider_api_key("unknown", "provider-api-key-secret-value-123456789")
    except vault.UpstoxCredentialError as error:
        assert error.status_code == 422
    else:
        raise AssertionError("Unknown providers must not be stored")

    try:
        vault.store_ai_provider_api_key("groq", "too-short")
    except vault.UpstoxCredentialError as error:
        assert error.status_code == 422
    else:
        raise AssertionError("Short provider keys must be rejected")
