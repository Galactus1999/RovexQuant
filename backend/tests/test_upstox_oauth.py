import asyncio
from urllib.parse import parse_qs, urlparse

from cryptography.fernet import Fernet
import pytest

from rovexquant.market_data import upstox_oauth as oauth


def configure(monkeypatch, tmp_path):
    monkeypatch.setenv("UPSTOX_CLIENT_ID", "client-id")
    monkeypatch.setenv("UPSTOX_CLIENT_SECRET", "client-secret")
    monkeypatch.setenv("UPSTOX_REDIRECT_URI", "https://quant.example.com/api/v1/integrations/upstox/callback")
    monkeypatch.setenv("QUANT_OAUTH_STATE_SECRET", "state-secret-long-random-value")
    monkeypatch.setenv("QUANT_TOKEN_ENCRYPTION_KEY", Fernet.generate_key().decode())
    monkeypatch.setenv("QUANT_TOKEN_DB_PATH", str(tmp_path / "secrets.sqlite3"))


def test_authorization_url_has_exact_redirect_and_signed_state(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    url = oauth.build_authorization_url()
    query = parse_qs(urlparse(url).query)
    assert url.startswith("https://api.upstox.com/v2/login/authorization/dialog?")
    assert query["client_id"] == ["client-id"]
    assert query["redirect_uri"] == [
        "https://quant.example.com/api/v1/integrations/upstox/callback"
    ]
    assert query["response_type"] == ["code"]
    assert oauth._verify_state(query["state"][0], "state-secret-long-random-value")


def test_state_rejects_tampering_and_replay(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    url = oauth.build_authorization_url()
    state = parse_qs(urlparse(url).query)["state"][0]
    with pytest.raises(oauth.UpstoxOAuthError):
        oauth._verify_state(state + "tampered", "state-secret-long-random-value")
    nonce = oauth._verify_state(state, "state-secret-long-random-value")
    oauth._consume_nonce(nonce)
    with pytest.raises(oauth.UpstoxOAuthError, match="already been used"):
        oauth._consume_nonce(nonce)


def test_access_token_is_encrypted_at_rest(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    token = "highly-sensitive-upstox-access-token"
    encrypted = oauth._fernet(
        __import__("os").environ["QUANT_TOKEN_ENCRYPTION_KEY"]
    ).encrypt(token.encode())
    with oauth._connect() as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at) VALUES (?, ?, ?)",
            ("upstox", encrypted, 1),
        )
    assert oauth.get_upstox_access_token() == token
    raw_db = (tmp_path / "secrets.sqlite3").read_bytes()
    assert token.encode() not in raw_db
    assert oauth.upstox_connection_status()["connected"] is True


def test_exchange_requires_https_and_all_server_secrets(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    monkeypatch.setenv("UPSTOX_REDIRECT_URI", "http://insecure.example/callback")
    with pytest.raises(oauth.UpstoxOAuthError, match="HTTPS"):
        oauth.build_authorization_url()
