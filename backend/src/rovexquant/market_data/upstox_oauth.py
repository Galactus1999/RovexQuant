"""Server-side Upstox OAuth and encrypted token persistence.

The mobile client never receives the Upstox access token or client secret.
"""
from __future__ import annotations

import base64
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from pathlib import Path
from collections.abc import Iterator
from urllib.parse import urlencode

import httpx
from cryptography.fernet import Fernet, InvalidToken


class UpstoxOAuthError(Exception):
    def __init__(self, message: str, status_code: int = 400):
        super().__init__(message)
        self.status_code = status_code


def _settings() -> tuple[str, str, str, str, str]:
    client_id = os.getenv("UPSTOX_CLIENT_ID", "").strip()
    client_secret = os.getenv("UPSTOX_CLIENT_SECRET", "").strip()
    redirect_uri = os.getenv("UPSTOX_REDIRECT_URI", "").strip()
    state_secret = os.getenv("QUANT_OAUTH_STATE_SECRET", "").strip()
    encryption_key = os.getenv("QUANT_TOKEN_ENCRYPTION_KEY", "").strip()
    if not all((client_id, client_secret, redirect_uri, state_secret, encryption_key)):
        raise UpstoxOAuthError("Upstox OAuth is not configured on the server", 503)
    if not redirect_uri.startswith("https://"):
        raise UpstoxOAuthError("Upstox OAuth redirect URI must use HTTPS", 503)
    return client_id, client_secret, redirect_uri, state_secret, encryption_key


def _db_path() -> Path:
    return Path(os.getenv("QUANT_TOKEN_DB_PATH", "./quant-secrets.sqlite3"))


@contextmanager
def _connect() -> Iterator[sqlite3.Connection]:
    path = _db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path, timeout=10)
    try:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute(
            "CREATE TABLE IF NOT EXISTS oauth_nonces "
            "(nonce TEXT PRIMARY KEY, created_at INTEGER NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE IF NOT EXISTS provider_tokens "
            "(provider TEXT PRIMARY KEY, encrypted_token BLOB NOT NULL, updated_at INTEGER NOT NULL)"
        )
        connection.commit()
        yield connection
    finally:
        connection.close()


def _fernet(key: str) -> Fernet:
    try:
        return Fernet(key.encode("ascii"))
    except (ValueError, UnicodeEncodeError) as error:
        raise UpstoxOAuthError("QUANT_TOKEN_ENCRYPTION_KEY must be a valid Fernet key", 503) from error


def _sign_state(payload: dict[str, object], secret: str) -> str:
    raw = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    encoded = base64.urlsafe_b64encode(raw).decode().rstrip("=")
    signature = hmac.new(secret.encode(), encoded.encode(), hashlib.sha256).hexdigest()
    return f"{encoded}.{signature}"


def _verify_state(state: str, secret: str) -> str:
    try:
        encoded, signature = state.split(".", 1)
        expected = hmac.new(secret.encode(), encoded.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            raise ValueError("signature")
        raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        payload = json.loads(raw)
        issued = int(payload["iat"])
        nonce = str(payload["nonce"])
        if abs(int(time.time()) - issued) > 600 or len(nonce) != 32:
            raise ValueError("expired")
        return nonce
    except (ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise UpstoxOAuthError("Invalid or expired OAuth state; restart Upstox login", 400) from error


def build_authorization_url() -> str:
    client_id, _, redirect_uri, state_secret, _ = _settings()
    state = _sign_state({"iat": int(time.time()), "nonce": secrets.token_hex(16)}, state_secret)
    return "https://api.upstox.com/v2/login/authorization/dialog?" + urlencode({
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "state": state,
    })


def _consume_nonce(nonce: str) -> None:
    with _connect() as connection:
        connection.execute("DELETE FROM oauth_nonces WHERE created_at < ?", (int(time.time()) - 900,))
        try:
            connection.execute(
                "INSERT INTO oauth_nonces(nonce, created_at) VALUES (?, ?)",
                (nonce, int(time.time())),
            )
        except sqlite3.IntegrityError as error:
            raise UpstoxOAuthError("OAuth state has already been used", 400) from error


async def exchange_authorization_code(code: str, state: str) -> None:
    if not code or len(code) > 2048:
        raise UpstoxOAuthError("Missing or invalid Upstox authorization code", 400)
    client_id, client_secret, redirect_uri, state_secret, encryption_key = _settings()
    nonce = _verify_state(state, state_secret)
    _consume_nonce(nonce)
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=5.0)) as client:
            response = await client.post(
                "https://api.upstox.com/v2/login/authorization/token",
                data={
                    "code": code,
                    "client_id": client_id,
                    "client_secret": client_secret,
                    "redirect_uri": redirect_uri,
                    "grant_type": "authorization_code",
                },
                headers={"Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"},
            )
    except httpx.RequestError as error:
        raise UpstoxOAuthError("Unable to reach Upstox token service; authorization code may need a new login", 502) from error
    if response.status_code != 200:
        raise UpstoxOAuthError(f"Upstox token exchange failed (HTTP {response.status_code}); restart login", 502)
    try:
        access_token = response.json().get("access_token")
    except (ValueError, AttributeError) as error:
        raise UpstoxOAuthError("Upstox returned an invalid token response", 502) from error
    if not isinstance(access_token, str) or not access_token.strip():
        raise UpstoxOAuthError("Upstox response did not contain an access token", 502)
    encrypted = _fernet(encryption_key).encrypt(access_token.strip().encode())
    with _connect() as connection:
        connection.execute(
            "INSERT INTO provider_tokens(provider, encrypted_token, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(provider) DO UPDATE SET encrypted_token=excluded.encrypted_token, "
            "updated_at=excluded.updated_at",
            ("upstox", encrypted, int(time.time())),
        )


def get_upstox_access_token() -> str | None:
    encryption_key = os.getenv("QUANT_TOKEN_ENCRYPTION_KEY", "").strip()
    if not encryption_key:
        return None
    try:
        with _connect() as connection:
            row = connection.execute(
                "SELECT encrypted_token FROM provider_tokens WHERE provider='upstox'"
            ).fetchone()
        if not row:
            return None
        return _fernet(encryption_key).decrypt(row[0]).decode()
    except (sqlite3.Error, InvalidToken, UnicodeDecodeError, UpstoxOAuthError):
        return None


def upstox_connection_status() -> dict[str, object]:
    token = get_upstox_access_token()
    return {"provider_id": "upstox", "connected": token is not None,
            "credential_storage": "encrypted_server_side" if token else "not_connected"}
