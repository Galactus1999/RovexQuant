import asyncio
import json
import time

import pytest
from fastapi.testclient import TestClient

from rovexquant.auth import AppAuthError, issue_app_session, verify_app_session
from rovexquant.api.main import app
from rovexquant.market_data import upstox_market_data_pb2
from rovexquant.market_data.streaming import decode_feed_message


def configure(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-user")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "long-test-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "s" * 48)


def test_app_session_is_signed_and_expires(monkeypatch):
    configure(monkeypatch)
    token, lifetime = issue_app_session("quant-user", "long-test-password")
    assert 300 <= lifetime <= 86400
    assert verify_app_session(token)["sub"] == "quant-user"


def test_app_session_rejects_wrong_password_and_tampering(monkeypatch):
    configure(monkeypatch)
    with pytest.raises(AppAuthError):
        issue_app_session("quant-user", "wrong-password")
    token, _ = issue_app_session("quant-user", "long-test-password")
    with pytest.raises(AppAuthError):
        verify_app_session(token[:-1] + ("A" if token[-1] != "A" else "B"))


def test_app_login_and_protected_market_providers(monkeypatch):
    configure(monkeypatch)
    client = TestClient(app)
    denied = client.get("/api/v1/app/market/providers")
    assert denied.status_code == 401
    login = client.post("/api/v1/auth/session", json={
        "username": "quant-user", "password": "long-test-password"
    })
    assert login.status_code == 200
    token = login.json()["access_token"]
    response = client.get("/api/v1/app/market/providers", headers={
        "Authorization": f"Bearer {token}"
    })
    assert response.status_code == 200
    assert "token" not in response.text.lower()


def test_protobuf_market_feed_decodes_to_normalized_json():
    response = upstox_market_data_pb2.FeedResponse()
    response.type = upstox_market_data_pb2.live_feed
    response.currentTs = int(time.time() * 1000)
    feed = response.feeds["NSE_INDEX|Nifty 50"]
    feed.fullFeed.indexFF.ltpc.ltp = 24100.5
    feed.fullFeed.indexFF.ltpc.cp = 24000.0
    feed.fullFeed.indexFF.ltpc.ltt = int(time.time() * 1000)
    feed.fullFeed.indexFF.ltpc.ltq = 0
    decoded = decode_feed_message(response.SerializeToString())
    assert decoded["type"] == "live_feed"
    quote = decoded["quotes"][0]
    assert quote["instrument_key"] == "NSE_INDEX|Nifty 50"
    assert quote["last_price"] == 24100.5
    assert quote["net_change"] == 100.5
    assert quote["quality"] == "VALID"
