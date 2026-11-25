import asyncio
import json
from datetime import datetime, timezone

import httpx
import pytest

from rovexquant.market_data.providers import (
    DhanQuoteProvider, MarketQuote, ProviderError, QuoteInstrument, UpstoxV3QuoteProvider,
)
from rovexquant.market_data.service import MarketGatewayError, fetch_quotes, provider_status


def instrument():
    return QuoteInstrument(
        symbol="NIFTY BANK",
        upstox_instrument_key="NSE_INDEX|Nifty Bank",
        dhan_exchange_segment="IDX_I",
        dhan_security_id="25",
    )


def test_upstox_v3_quote_normalizes_snapshot():
    def handler(request):
        assert request.url.path == "/v3/market-quote/quotes"
        assert request.headers["Authorization"] == "Bearer secret"
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        return httpx.Response(200, json={"status": "success", "data": {
            "NSE_INDEX|Nifty Bank": {
                "last_price": 51000.0, "prev_close_price": 50000.0,
                "net_change": 1000.0, "volume": 1200, "timestamp": now_ms,
                "year_high": 55000.0, "year_low": 42000.0,
            }
        }})
    provider = UpstoxV3QuoteProvider("secret", transport=httpx.MockTransport(handler))
    quotes = asyncio.run(provider.get_quotes([instrument()]))
    assert len(quotes) == 1
    assert quotes[0].symbol == "NIFTY BANK"
    assert quotes[0].last_price == 51000.0
    assert quotes[0].change_percent == 2.0
    assert quotes[0].volume == 1200
    assert quotes[0].year_high == 55000.0
    assert quotes[0].year_low == 42000.0
    assert quotes[0].exchange_timestamp is not None
    assert quotes[0].quality == "VALID"


def test_upstox_auth_failure_is_not_retryable():
    def handler(_request):
        return httpx.Response(401, json={"message": "invalid"})
    provider = UpstoxV3QuoteProvider("bad-token", transport=httpx.MockTransport(handler))
    with pytest.raises(ProviderError) as error:
        asyncio.run(provider.get_quotes([instrument()]))
    assert error.value.retryable is False


def test_dhan_quote_adapter_maps_security_ids_and_marks_missing_timestamp():
    def handler(request):
        assert request.url.path == "/v2/marketfeed/quote"
        assert request.headers["access-token"] == "dhan-secret"
        assert json.loads(request.content)["IDX_I"] == [25]
        return httpx.Response(200, json={"status": "success", "data": {
            "IDX_I": {"25": {
                "last_price": 51000.0, "net_change": 1000.0, "volume": 0,
                "ohlc": {"close": 50000.0}, "last_trade_time": "unknown",
            }}
        }})
    provider = DhanQuoteProvider(
        "client", "dhan-secret", transport=httpx.MockTransport(handler)
    )
    quotes = asyncio.run(provider.get_quotes([instrument()]))
    assert quotes[0].provider == "dhan"
    assert quotes[0].change_percent == 2.0
    assert quotes[0].quality == "TIMESTAMP_UNAVAILABLE"


def test_transient_primary_failure_uses_explicit_secondary_provider():
    class FakeProvider:
        def __init__(self, provider_id, fail):
            self.provider_id = provider_id
            self.fail = fail
        @property
        def configured(self):
            return True
        async def get_quotes(self, _instruments):
            if self.fail:
                raise ProviderError(self.provider_id, "temporarily unavailable", retryable=True, status_code=503)
            now = datetime.now(timezone.utc)
            return [MarketQuote(
                symbol="NIFTY BANK", provider=self.provider_id, last_price=51000.0,
                previous_close=50000.0, net_change=1000.0, change_percent=2.0,
                volume=1, exchange_timestamp=now, received_at=now, quality="VALID",
            )]
    selected, quotes, fallback = asyncio.run(fetch_quotes(
        [instrument()], ["upstox", "dhan"],
        {"upstox": FakeProvider("upstox", True), "dhan": FakeProvider("dhan", False)},
    ))
    assert selected == "dhan"
    assert fallback is True
    assert quotes[0].provider == "dhan"


def test_non_retryable_primary_failure_blocks_provider_switch():
    class FakeProvider:
        provider_id = "upstox"
        configured = True
        async def get_quotes(self, _instruments):
            raise ProviderError("upstox", "credential rejected", retryable=False, status_code=502)
    class BackupProvider:
        provider_id = "dhan"
        configured = True
        async def get_quotes(self, _instruments):
            raise AssertionError("must not be called")
    with pytest.raises(MarketGatewayError, match="credential rejected"):
        asyncio.run(fetch_quotes(
            [instrument()], ["upstox", "dhan"],
            {"upstox": FakeProvider(), "dhan": BackupProvider()},
        ))


def test_provider_status_never_exposes_credentials():
    status = provider_status({
        "upstox": UpstoxV3QuoteProvider("super-secret"),
        "dhan": DhanQuoteProvider("client-id", "other-secret"),
    })
    assert status == [
        {"provider_id": "upstox", "configured": True},
        {"provider_id": "dhan", "configured": True},
    ]
    assert "secret" not in str(status)


def test_market_provider_status_requires_server_gateway_token(monkeypatch):
    from fastapi.testclient import TestClient
    from rovexquant.api.main import app

    monkeypatch.setenv("QUANT_MARKET_GATEWAY_TOKEN", "server-only-token")
    response = TestClient(app).get("/api/v1/market/providers")
    assert response.status_code == 401


def test_market_provider_status_is_safe_and_reports_unconfigured(monkeypatch):
    from fastapi.testclient import TestClient
    from rovexquant.api.main import app

    monkeypatch.setenv("QUANT_MARKET_GATEWAY_TOKEN", "server-only-token")
    monkeypatch.delenv("UPSTOX_ANALYTICS_TOKEN", raising=False)
    monkeypatch.delenv("DHAN_CLIENT_ID", raising=False)
    monkeypatch.delenv("DHAN_ACCESS_TOKEN", raising=False)
    response = TestClient(app).get(
        "/api/v1/market/providers",
        headers={"x-quant-market-gateway-key": "server-only-token"},
    )
    assert response.status_code == 200
    assert response.json() == {"providers": [
        {"provider_id": "upstox", "configured": False},
        {"provider_id": "dhan", "configured": False},
    ]}


def test_market_quotes_endpoint_returns_normalized_quote(monkeypatch):
    from fastapi.testclient import TestClient
    from rovexquant.api.main import app
    from rovexquant.market_data import service

    class FakeProvider:
        provider_id = "upstox"
        configured = True
        async def get_quotes(self, instruments):
            now = datetime.now(timezone.utc)
            return [MarketQuote(
                symbol=instruments[0].symbol, provider="upstox", last_price=51000.0,
                previous_close=50000.0, net_change=1000.0, change_percent=2.0,
                volume=10, exchange_timestamp=now, received_at=now, quality="VALID",
            )]

    monkeypatch.setenv("QUANT_MARKET_GATEWAY_TOKEN", "server-only-token")
    monkeypatch.setattr(service, "build_providers", lambda: {"upstox": FakeProvider()})
    response = TestClient(app).post(
        "/api/v1/market/quotes",
        headers={"x-quant-market-gateway-key": "server-only-token"},
        json={"provider_order": ["upstox"], "instruments": [{
            "symbol": "NIFTY BANK", "upstox_instrument_key": "NSE_INDEX|Nifty Bank"
        }]},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "upstox"
    assert body["complete"] is True
    assert body["quotes"][0]["change_percent"] == 2.0
    assert body["quotes"][0]["quality"] == "VALID"
