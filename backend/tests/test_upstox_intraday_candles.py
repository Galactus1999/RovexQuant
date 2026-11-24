import asyncio
from datetime import date

import httpx
import pytest

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
        requested.append((request.url.path, request.headers.get("Authorization")))
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


def test_upstox_v3_rejects_unsupported_intervals_and_excessive_history():
    with pytest.raises(upstox_candles.UpstoxCandleError, match="Unsupported"):
        upstox_candles.interval_contract("2m")
    with pytest.raises(upstox_candles.UpstoxCandleError, match="limited"):
        asyncio.run(upstox_candles.fetch_intraday_candles(
            "NSE", "RELIANCE", "1m", date(2026, 10, 1), date(2026, 11, 24),
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
