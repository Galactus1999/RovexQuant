import asyncio
from datetime import date

from fastapi.testclient import TestClient
import httpx
import pytest

from rovexquant.api.main import app
from rovexquant.api.routers import market as market_routes
from rovexquant.auth import issue_app_session
from rovexquant.market_data import upstox_candles

def test_upstox_v3_merges_historical_and_current_session_candles(monkeypatch):
    today = date(2026, 11, 24)
    yesterday = date(2026, 11, 23)
    monkeypatch.setattr(upstox_candles, "get_upstox_analytics_token", lambda: "fake-token")
    monkeypatch.setattr(upstox_candles, "get_nse_equity_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_EQ|INE002A01018",
        "name": "RELIANCE INDUSTRIES LIMITED",
        "trading_symbol": "RELIANCE",
    }]))

    requested = []

    async def handler(request):
        requested.append((request.url.raw_path.decode("ascii"), request.headers.get("Authorization")))
        if "/historical-candle/" in request.url.path and "/intraday/" not in request.url.path:
            return httpx.Response(200, json={"data": {"candles": [[
                f"{yesterday.isoformat()}T15:30:00+05:30", 100, 103, 99, 102, 1000
            ]]}})
        return httpx.Response(200, json={"data": {"candles": [[
            f"{today.isoformat()}T09:15:00+05:30", 102, 104, 101, 103, 500
        ]]}})

    result = asyncio.run(upstox_candles.fetch_intraday_candles(
        "NSE", "RELIANCE", "5m", yesterday, today,
        transport=httpx.MockTransport(handler), today=today,
    ))

    assert result["provider"] == "UPSTOX_V3"
    assert result["volume_available"] is True
    assert result["count"] == 2
    assert [item["timestamp"].date() for item in result["bars"]] == [yesterday, today]
    assert all(auth == "Bearer fake-token" for _, auth in requested)
    assert any("/historical-candle/NSE_EQ%7CINE002A01018/minutes/5/" in path for path, _ in requested)
    assert any("/historical-candle/intraday/NSE_EQ%7CINE002A01018/minutes/5" in path for path, _ in requested)


def test_upstox_v3_resolves_index_and_never_marks_index_volume_available(monkeypatch):
    today = date(2026, 11, 24)
    monkeypatch.setattr(upstox_candles, "get_upstox_analytics_token", lambda: "fake-token")
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty Bank",
        "name": "Nifty Bank",
        "trading_symbol": "Nifty Bank",
    }]))
    transport = httpx.MockTransport(lambda request: httpx.Response(
        200, json={"data": {"candles": [[
            f"{today.isoformat()}T09:15:00+05:30", 50000, 50100, 49900, 50050, 0
        ]]}}
    ))

    result = asyncio.run(upstox_candles.fetch_intraday_candles(
        "NSE", "NIFTY BANK", "1h", today, today, transport=transport, today=today
    ))

    assert result["instrument_key"] == "NSE_INDEX|Nifty Bank"
    assert result["volume_available"] is False



def test_nifty_prefixed_etf_resolves_as_equity_not_index(monkeypatch):
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty 50",
        "name": "Nifty 50",
        "trading_symbol": "Nifty 50",
    }]))
    monkeypatch.setattr(upstox_candles, "get_nse_equity_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_EQ|INF204KB17I5",
        "name": "NIPPON INDIA ETF NIFTY 50 BEES",
        "trading_symbol": "NIFTYBEES",
    }]))
    assert asyncio.run(upstox_candles.resolve_nse_instrument_key("NIFTYBEES")) == "NSE_EQ|INF204KB17I5"

def test_upstox_v3_rejects_unsupported_intervals_and_excessive_history():
    with pytest.raises(upstox_candles.UpstoxCandleError, match="Unsupported"):
        upstox_candles.interval_contract("2m")
    with pytest.raises(upstox_candles.UpstoxCandleError, match="limited"):
        asyncio.run(upstox_candles.fetch_intraday_candles(
            "NSE", "RELIANCE", "1m", date(2026, 10, 1), date(2026, 11, 24),
            today=date(2026, 11, 24),
        ))



def test_upstox_v3_fetches_daily_index_history_and_current_session(monkeypatch):
    today = date(2026, 11, 24)
    yesterday = date(2026, 11, 23)
    monkeypatch.setattr(upstox_candles, "get_upstox_analytics_token", lambda: "fake-token")
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty Bank",
        "name": "Nifty Bank",
        "trading_symbol": "Nifty Bank",
    }]))
    requested = []

    async def handler(request):
        requested.append(request.url.raw_path.decode("ascii"))
        if "/intraday/" in request.url.path:
            return httpx.Response(200, json={"data": {"candles": [[
                f"{today.isoformat()}T00:00:00+05:30", 50050, 50200, 49950, 50100, 0
            ]]}})
        return httpx.Response(200, json={"data": {"candles": [[
            f"{yesterday.isoformat()}T00:00:00+05:30", 49800, 50100, 49700, 50050, 0
        ]]}})

    result = asyncio.run(upstox_candles.fetch_intraday_candles(
        "NSE", "NIFTY BANK", "1d", yesterday, today,
        transport=httpx.MockTransport(handler), today=today,
    ))

    assert result["provider"] == "UPSTOX_V3"
    assert result["data_mode"] == "DAILY_HISTORY_AND_CURRENT_SESSION"
    assert result["volume_available"] is False
    assert result["count"] == 2
    assert [item["timestamp"].date() for item in result["bars"]] == [yesterday, today]
    assert any("/historical-candle/NSE_INDEX%7CNifty%20Bank/days/1/" in path for path in requested)
    assert any("/historical-candle/intraday/NSE_INDEX%7CNifty%20Bank/days/1" in path for path in requested)


def test_upstox_v3_resolves_nifty_financial_services_official_master_alias(monkeypatch):
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty Fin Service",
        "name": "Nifty Fin Service",
        "trading_symbol": "Nifty Fin Service",
    }]))
    assert asyncio.run(upstox_candles.resolve_nse_instrument_key(
        "NIFTY FINANCIAL SERVICES"
    )) == "NSE_INDEX|Nifty Fin Service"




def test_upstox_v3_resolves_oil_and_gas_catalog_alias(monkeypatch):
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty Oil & Gas",
        "name": "Nifty Oil & Gas",
        "trading_symbol": "Nifty Oil & Gas",
    }]))
    assert asyncio.run(upstox_candles.resolve_nse_instrument_key(
        "NIFTY OIL AND GAS INDEX"
    )) == "NSE_INDEX|Nifty Oil & Gas"

def test_upstox_v3_rejects_daily_history_beyond_provider_decade_limit():
    with pytest.raises(upstox_candles.UpstoxCandleError, match="limited"):
        asyncio.run(upstox_candles.fetch_intraday_candles(
            "NSE", "NIFTY BANK", "1d", date(2016, 11, 1), date(2026, 11, 24),
            today=date(2026, 11, 24),
        ))

def test_upstox_v3_rejects_malformed_ohlc_and_naive_timestamps():
    with pytest.raises(upstox_candles.UpstoxCandleError, match="invalid OHLCV"):
        upstox_candles._parse_candles({"data": {"candles": [[
            "2026-11-24T09:15:00+05:30", 100, 99, 98, 101, 1
        ]]}})
    with pytest.raises(upstox_candles.UpstoxCandleError, match="timezone"):
        upstox_candles._parse_candles({"data": {"candles": [[
            "2026-11-24T09:15:00", 100, 101, 99, 100, 1
        ]]}})


def test_intraday_candle_route_requires_app_session_and_passes_query_symbol(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-user")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "long-test-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "s" * 48)

    async def fake_fetch(exchange, symbol, interval, from_date, to_date):
        assert exchange == "NSE"
        assert symbol == "NIFTY FINANCIAL SERVICES 25/50"
        assert interval == "5m"
        assert from_date == date(2026, 11, 23)
        assert to_date == date(2026, 11, 24)
        return {"provider": "UPSTOX_V3", "count": 0, "bars": []}

    monkeypatch.setattr(market_routes, "fetch_intraday_candles", fake_fetch)
    client = TestClient(app)
    url = (
        "/api/v1/app/market/candles?exchange=NSE"
        "&symbol=NIFTY+FINANCIAL+SERVICES+25%2F50"
        "&interval=5m&from=2026-11-23&to=2026-11-24"
    )
    assert client.get(url).status_code == 401
    token, _ = issue_app_session("quant-user", "long-test-password")
    response = client.get(url, headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert response.json()["provider"] == "UPSTOX_V3"



def test_upstox_v3_resolves_healthcare_index_without_master_suffix(monkeypatch):
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty Healthcare Index",
        "name": "Nifty Healthcare Index",
        "trading_symbol": "NIFTY HEALTHCARE INDEX",
    }]))
    assert asyncio.run(upstox_candles.resolve_nse_instrument_key(
        "NIFTY HEALTHCARE"
    )) == "NSE_INDEX|Nifty Healthcare Index"


def test_upstox_v3_resolves_punctuated_financial_services_ex_bank_name(monkeypatch):
    monkeypatch.setattr(upstox_candles, "get_nse_index_instruments", lambda: asyncio.sleep(0, result=[{
        "instrument_key": "NSE_INDEX|Nifty Financial Services Ex-Bank",
        "name": "Nifty Financial Services Ex-Bank Index",
        "trading_symbol": "NIFTY FINANCIAL SERVICES EX-BANK",
    }]))
    assert asyncio.run(upstox_candles.resolve_nse_instrument_key(
        "NIFTY FINANCIAL SERVICES EX BANK"
    )) == "NSE_INDEX|Nifty Financial Services Ex-Bank"
