import os

from cryptography.fernet import Fernet
import pytest
from fastapi.testclient import TestClient

from rovexquant.market_data import upstox_credentials as vault


def configure(monkeypatch, tmp_path):
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())
    monkeypatch.setenv("QUANT_TOKEN_DB_PATH", str(tmp_path / "secrets.sqlite3"))


def test_analytics_token_is_encrypted_at_rest(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    token = "highly-sensitive-upstox-analytics-token"
    vault.store_upstox_analytics_token(token)
    assert vault.get_upstox_analytics_token() == token
    raw_db = (tmp_path / "secrets.sqlite3").read_bytes()
    assert token.encode() not in raw_db
    status = vault.upstox_analytics_status()
    assert status["connected"] is True
    assert status["credential_type"] == "analytics_read_only"
    assert status["credential_storage"] == "encrypted_server_side"


def test_analytics_token_expires_after_one_year(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    clock = {"now": 1_800_000_000}
    monkeypatch.setattr(vault.time, "time", lambda: clock["now"])
    vault.store_upstox_analytics_token("valid-upstox-analytics-token-value")
    assert vault.get_upstox_analytics_token() is not None
    clock["now"] += 365 * 24 * 60 * 60 + 1
    assert vault.get_upstox_analytics_token() is None
    assert vault.upstox_analytics_status()["connected"] is False


def test_invalid_or_missing_encryption_key_fails_closed(monkeypatch, tmp_path):
    monkeypatch.setenv("QUANT_TOKEN_DB_PATH", str(tmp_path / "secrets.sqlite3"))
    monkeypatch.delenv("QUANT_TOKEN_ENCRYPTION_KEY", raising=False)
    with pytest.raises(vault.UpstoxCredentialError, match="not configured"):
        vault.store_upstox_analytics_token("valid-upstox-analytics-token-value")
    assert vault.get_upstox_analytics_token() is None


def test_admin_endpoint_stores_token_without_returning_it(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    monkeypatch.setenv("QUANT_ADMIN_GATEWAY_TOKEN", "server-admin-secret")
    client = TestClient(__import__("rovexquant.api.main", fromlist=["app"]).app)
    token = "sensitive-analytics-token-value-123456"
    denied = client.post(
        "/api/v1/integrations/upstox/analytics-token", json={"token": token}
    )
    assert denied.status_code == 401
    response = client.post(
        "/api/v1/integrations/upstox/analytics-token",
        headers={"x-quant-admin-key": "server-admin-secret"},
        json={"token": token},
    )
    assert response.status_code == 200
    assert token not in response.text
    assert vault.get_upstox_analytics_token() == token
    status = client.get(
        "/api/v1/integrations/upstox/status",
        headers={"x-quant-admin-key": "server-admin-secret"},
    )
    assert status.status_code == 200
    assert status.json()["connected"] is True
