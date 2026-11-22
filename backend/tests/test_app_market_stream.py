import asyncio
import json
import time

import pytest
from fastapi.testclient import TestClient

from rovexquant.auth import AppAuthError, issue_app_session, verify_app_session
from rovexquant.api.routers import market as market_routes
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



def test_app_index_master_requires_session_and_returns_verified_keys(monkeypatch):
    configure(monkeypatch)

    async def fake_master():
        return [{
            "instrument_key": "NSE_INDEX|Nifty 50",
            "name": "Nifty 50",
            "trading_symbol": "Nifty 50",
        }]

    monkeypatch.setattr(market_routes, "get_nse_index_instruments", fake_master)
    client = TestClient(app)
    assert client.get("/api/v1/app/market/index-instruments").status_code == 401
    token, _ = issue_app_session("quant-user", "long-test-password")
    response = client.get("/api/v1/app/market/index-instruments", headers={
        "Authorization": f"Bearer {token}"
    })
    assert response.status_code == 200
    assert response.json()["instruments"][0]["instrument_key"] == "NSE_INDEX|Nifty 50"


def test_websocket_route_authenticates_and_forwards_market_ticks(monkeypatch):
    configure(monkeypatch)
    token, _ = issue_app_session("quant-user", "long-test-password")

    async def fake_stream(websocket, keys, mode):
        assert keys == ["NSE_INDEX|Nifty 50"]
        assert mode == "full"
        await websocket.send_json({"type": "live_feed", "quotes": [{
            "instrument_key": "NSE_INDEX|Nifty 50", "last_price": 24000.0
        }]})
        await websocket.close(code=1000)

    monkeypatch.setattr(market_routes, "stream_upstox_market_data", fake_stream)
    client = TestClient(app)
    with client.websocket_connect(
        "/api/v1/app/market/stream",
        headers={"Authorization": f"Bearer {token}"},
    ) as websocket:
        websocket.send_json({"instrument_keys": ["NSE_INDEX|Nifty 50"], "mode": "full"})
        message = websocket.receive_json()
        assert message["type"] == "live_feed"
        assert message["quotes"][0]["last_price"] == 24000.0


def test_websocket_route_rejects_missing_session():
    client = TestClient(app)
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/app/market/stream"):
            pass


def test_stream_bridge_performs_authorized_v3_subscription_and_decodes_tick(monkeypatch):
    from rovexquant.market_data import streaming
    from rovexquant.market_data import upstox_credentials

    response = upstox_market_data_pb2.FeedResponse()
    response.type = upstox_market_data_pb2.live_feed
    response.currentTs = int(time.time() * 1000)
    ltpc = response.feeds["NSE_INDEX|Nifty 50"].fullFeed.indexFF.ltpc
    ltpc.ltp = 24000.0
    ltpc.cp = 23900.0
    ltpc.ltt = int(time.time() * 1000)
    equity_ltpc = response.feeds["NSE_EQ|INE002A01018"].ltpc
    equity_ltpc.ltp = 1500.0
    equity_ltpc.cp = 1490.0
    equity_ltpc.ltt = int(time.time() * 1000)
    payload = response.SerializeToString()

    class FakeHttpResponse:
        status_code = 200
        def json(self):
            return {"data": {"authorized_redirect_uri": "wss://stream.upstox.test/one-time"}}

    class FakeHttpClient:
        async def __aenter__(self):
            return self
        async def __aexit__(self, *_args):
            return None
        async def get(self, url, headers):
            assert url.endswith("/v3/feed/market-data-feed/authorize")
            assert headers["Authorization"] == "Bearer fake-analytics-token"
            assert headers["Accept"] == "application/json"
            return FakeHttpResponse()

    class FakeUpstream:
        def __init__(self):
            self.sent = []
        async def send(self, message):
            self.sent.append(message)
        def __aiter__(self):
            async def messages():
                yield payload
            return messages()

    class FakeUpstreamContext:
        def __init__(self):
            self.connection = FakeUpstream()
        async def __aenter__(self):
            return self.connection
        async def __aexit__(self, *_args):
            return None

    class FakeClientWebSocket:
        def __init__(self):
            self.messages = []
        async def send_json(self, message):
            self.messages.append(message)
        async def receive(self):
            await asyncio.Future()

    context = FakeUpstreamContext()
    monkeypatch.setattr(upstox_credentials, "get_upstox_analytics_token", lambda: "fake-analytics-token")
    monkeypatch.setattr(streaming.httpx, "AsyncClient", lambda **_kwargs: FakeHttpClient())
    monkeypatch.setattr(streaming.websockets, "connect", lambda *_args, **_kwargs: context)
    client_socket = FakeClientWebSocket()

    asyncio.run(streaming.stream_upstox_market_data(
        client_socket, ["NSE_INDEX|Nifty 50", "NSE_EQ|INE002A01018"], "ltpc"
    ))
    assert isinstance(context.connection.sent[0], bytes), "Upstox V3 control messages must use a binary WebSocket frame"
    subscription = json.loads(context.connection.sent[0].decode("utf-8"))
    assert subscription["method"] == "sub"
    assert subscription["data"] == {
        "mode": "ltpc",
        "instrumentKeys": ["NSE_INDEX|Nifty 50", "NSE_EQ|INE002A01018"],
    }
    quotes_by_key = {
        quote["instrument_key"]: quote for quote in client_socket.messages[0]["quotes"]
    }
    assert quotes_by_key["NSE_INDEX|Nifty 50"]["last_price"] == 24000.0
    assert quotes_by_key["NSE_EQ|INE002A01018"]["last_price"] == 1500.0


def test_stream_bridge_rejects_unapproved_instrument_keys_before_network(monkeypatch):
    from rovexquant.market_data import upstox_credentials
    from rovexquant.market_data.streaming import StreamError, stream_upstox_market_data

    monkeypatch.setattr(upstox_credentials, "get_upstox_analytics_token", lambda: "fake-analytics-token")
    with pytest.raises(StreamError, match="Only verified NSE index or equity"):
        asyncio.run(stream_upstox_market_data(object(), ["BSE_INDEX|SENSEX"], "full"))



def test_mobile_session_validation_endpoint(monkeypatch):
    configure(monkeypatch)
    client = TestClient(app)
    assert client.get("/api/v1/app/auth/session").status_code == 401
    token, _ = issue_app_session("quant-user", "long-test-password")
    response = client.get("/api/v1/app/auth/session", headers={
        "Authorization": f"Bearer {token}"
    })
    assert response.status_code == 200
    assert response.json()["authenticated"] is True
    assert isinstance(response.json()["expires_at"], int)
    assert "access_token" not in response.text


def test_mobile_session_validation_rejects_expired_token(monkeypatch):
    configure(monkeypatch)
    import base64
    import hashlib
    import hmac
    import json

    header = base64.urlsafe_b64encode(b'{"alg":"HS256","typ":"JWT"}').rstrip(b"=").decode()
    payload_data = {
        "iss": "quant-api", "aud": "quant-mobile", "sub": "quant-user",
        "iat": int(time.time()) - 600, "exp": int(time.time()) - 1,
    }
    payload = base64.urlsafe_b64encode(
        json.dumps(payload_data, separators=(",", ":"), sort_keys=True).encode()
    ).rstrip(b"=").decode()
    signing_input = f"{header}.{payload}".encode()
    signature = base64.urlsafe_b64encode(
        hmac.new(b"s" * 48, signing_input, hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    response = TestClient(app).get(
        "/api/v1/app/auth/session",
        headers={"Authorization": f"Bearer {header}.{payload}.{signature}"},
    )
    assert response.status_code == 401



def test_app_equity_master_requires_session_and_returns_verified_nse_equities(monkeypatch):
    configure(monkeypatch)

    async def fake_master():
        return [{
            "instrument_key": "NSE_EQ|INE002A01018",
            "name": "RELIANCE INDUSTRIES LTD",
            "trading_symbol": "RELIANCE",
            "isin": "INE002A01018",
        }]

    monkeypatch.setattr(market_routes, "get_nse_equity_instruments", fake_master)
    client = TestClient(app)
    assert client.get("/api/v1/app/market/equity-instruments").status_code == 401
    token, _ = issue_app_session("quant-user", "long-test-password")
    response = client.get(
        "/api/v1/app/market/equity-instruments",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    assert response.json()["data_mode"] == "INSTRUMENT_REFERENCE"
    assert response.json()["instruments"][0]["instrument_key"] == "NSE_EQ|INE002A01018"
