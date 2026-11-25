"""Short-lived app sessions for Quant's mobile API; no provider secrets reach the client."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import time


class AppAuthError(Exception):
    def __init__(self, message: str, status_code: int = 401):
        super().__init__(message)
        self.status_code = status_code


_BASE64URL_SEGMENT = re.compile(r"^[A-Za-z0-9_-]+$")
_MAX_SESSION_TTL_SECONDS = 86400
_CLOCK_SKEW_SECONDS = 30


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _unb64(value: str) -> bytes:
    """Decode one canonical JWT base64url segment and reject ignored junk."""
    if not value or not _BASE64URL_SEGMENT.fullmatch(value):
        raise ValueError("Invalid base64url segment")
    padded = value + "=" * (-len(value) % 4)
    decoded = base64.b64decode(padded, altchars=b"-_", validate=True)
    if _b64(decoded) != value:
        raise ValueError("Non-canonical base64url segment")
    return decoded


def _secret() -> bytes:
    value = os.getenv("QUANT_APP_SESSION_SIGNING_KEY", "").strip()
    secret = value.encode("utf-8")
    if len(secret) < 32:
        raise AppAuthError("App session signing key must be configured with at least 32 bytes", 503)
    return secret


def issue_app_session(username: str, password: str) -> tuple[str, int]:
    expected_user = os.getenv("QUANT_APP_USERNAME", "")
    expected_password = os.getenv("QUANT_APP_PASSWORD", "")
    if not expected_user or not expected_password:
        raise AppAuthError("Mobile app login is not configured on the server", 503)
    if not hmac.compare_digest(username.encode(), expected_user.encode()) or not hmac.compare_digest(
        password.encode(), expected_password.encode()
    ):
        raise AppAuthError("Invalid username or password", 401)
    now = int(time.time())
    lifetime = min(max(int(os.getenv("QUANT_APP_SESSION_TTL_SECONDS", "14400")), 300), _MAX_SESSION_TTL_SECONDS)
    header = _b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = _b64(json.dumps({
        "iss": "quant-api", "aud": "quant-mobile", "sub": expected_user,
        "iat": now, "exp": now + lifetime,
    }, separators=(",", ":"), sort_keys=True).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = _b64(hmac.new(_secret(), signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}", lifetime


def verify_app_session(token: str | None) -> dict[str, object]:
    if not isinstance(token, str) or not token or len(token) > 4096:
        raise AppAuthError("A valid Quant app session is required", 401)
    try:
        header, payload, signature = token.split(".")
        signing_input = f"{header}.{payload}".encode("ascii")
        expected = _b64(hmac.new(_secret(), signing_input, hashlib.sha256).digest())
        if not hmac.compare_digest(expected, signature):
            raise AppAuthError("Invalid Quant app session", 401)

        decoded_header = json.loads(_unb64(header))
        claims = json.loads(_unb64(payload))
        if not isinstance(decoded_header, dict) or not isinstance(claims, dict):
            raise AppAuthError("Invalid Quant app session", 401)
        if decoded_header.get("alg") != "HS256" or decoded_header.get("typ") != "JWT":
            raise AppAuthError("Invalid Quant app session", 401)
        if claims.get("iss") != "quant-api" or claims.get("aud") != "quant-mobile":
            raise AppAuthError("Invalid Quant app session", 401)

        now = int(time.time())
        issued_at = claims.get("iat")
        expires_at = claims.get("exp")
        subject = claims.get("sub")
        if type(issued_at) is not int or type(expires_at) is not int:
            raise AppAuthError("Invalid Quant app session", 401)
        if not isinstance(subject, str) or not subject.strip():
            raise AppAuthError("Invalid Quant app session", 401)
        if issued_at > now + _CLOCK_SKEW_SECONDS:
            raise AppAuthError("Invalid Quant app session", 401)
        if expires_at <= now:
            raise AppAuthError("Quant app session has expired", 401)
        if expires_at <= issued_at or expires_at - issued_at > _MAX_SESSION_TTL_SECONDS:
            raise AppAuthError("Invalid Quant app session", 401)

        not_before = claims.get("nbf")
        if not_before is not None:
            if type(not_before) is not int or not_before > now + _CLOCK_SKEW_SECONDS:
                raise AppAuthError("Invalid Quant app session", 401)
        return claims
    except AppAuthError:
        raise
    except (ValueError, TypeError, KeyError, json.JSONDecodeError, UnicodeError) as error:
        raise AppAuthError("Invalid Quant app session", 401) from error


def bearer_claims(authorization: str | None) -> dict[str, object]:
    if not authorization or not authorization.startswith("Bearer "):
        raise AppAuthError("Bearer app session required", 401)
    return verify_app_session(authorization[7:].strip())
