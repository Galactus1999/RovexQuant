from datetime import date

import httpx
from fastapi.testclient import TestClient

from rovexquant.api import main as api
from rovexquant.auth import issue_app_session
from rovexquant.market_data.tejhq import TejHqEodProvider
from rovexquant.market_data import tejhq_service


def _session(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "test-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "test-signing-key-that-is-long-enough-123")
    return issue_app_session("quant-test", "test-password")[0]


def test_tejhq_research_endpoints_require_app_session():
    client = TestClient(api.app)
    response = client.get(
        "/api/v1/app/research/market-data/ohlcv/NSE/RELIANCE",
        params={"from": "2025-05-28", "to": "2025-05-28"},
    )
    assert response.status_code == 401


def test_tejhq_ohlcv_api_explicitly_labels_data_as_eod(monkeypatch):
    token = _session(monkeypatch)

    def handler(request):
        assert request.url.path == "/v1/ohlcv/nse/RELIANCE"
        return httpx.Response(200, json={"data": [{
            "date": "2025-05-28", "open": 1420.1, "high": 1422.6,
            "low": 1410.7, "close": 1412.9, "volume": 5944611,
        }]})

    provider = TejHqEodProvider(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(
        tejhq_service.TejHqEodProvider, "from_environment", classmethod(lambda cls: provider)
    )
    response = TestClient(api.app).get(
        "/api/v1/app/research/market-data/ohlcv/NSE/RELIANCE",
        params={"from": "2025-05-28", "to": "2025-05-28"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "tejhq_eod"
    assert body["data_mode"] == "END_OF_DAY"
    assert body["timestamp_semantics"].startswith("Trading date only")
    assert body["bars"][0]["close"] == 1412.9


def test_tejhq_adjusted_endpoint_reports_missing_free_key_without_network(monkeypatch):
    token = _session(monkeypatch)
    provider = TejHqEodProvider()
    monkeypatch.setattr(
        tejhq_service.TejHqEodProvider, "from_environment", classmethod(lambda cls: provider)
    )
    response = TestClient(api.app).get(
        "/api/v1/app/research/market-data/ohlcv/NSE/RELIANCE",
        params={"from": "2025-05-28", "to": "2025-05-28", "adjusted": "true"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 503
    assert "optional free API key" in response.json()["detail"]


def test_latest_eod_market_snapshot_is_authenticated_and_summarized(monkeypatch):
    from datetime import date

    token = _session(monkeypatch)

    async def fake_latest(exchange, *, max_lookback_days=10):
        assert exchange == "nse"
        assert max_lookback_days == 10
        return "tejhq_eod", date(2026, 11, 20), [
            {"symbol": "GAIN", "name": "Gainer Ltd", "series": "EQ", "close": 110, "prev_close": 100, "volume": 1000},
            {"symbol": "LOSS", "series": "EQ", "close": 90, "prev_close": 100, "volume": 2000},
            {"symbol": "BEONLY", "series": "BE", "close": 120, "prev_close": 100, "volume": 9000},
        ]

    monkeypatch.setattr(tejhq_service, "fetch_latest_snapshot", fake_latest)
    client = TestClient(api.app)
    unauthorized = client.get("/api/v1/app/market-data/eod/latest/nse")
    assert unauthorized.status_code == 401

    response = client.get(
        "/api/v1/app/market-data/eod/latest/nse",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "tejhq_eod"
    assert body["data_mode"] == "END_OF_DAY"
    assert body["trading_date"] == "2026-11-20"
    assert body["summary"]["advances"] == 1
    assert body["summary"]["declines"] == 1
    assert body["summary"]["equity_series_rows"] == 2
    assert body["summary"]["top_gainers"][0]["symbol"] == "GAIN"
    assert "rows" not in body
    assert "BEONLY" not in response.text


def test_latest_eod_market_snapshot_bounds_lookback_and_limit(monkeypatch):
    token = _session(monkeypatch)
    response = TestClient(api.app).get(
        "/api/v1/app/market-data/eod/latest/nse",
        params={"max_lookback_days": 30, "limit": 100},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 422


def test_latest_bse_eod_market_snapshot_uses_bse_equity_series(monkeypatch):
    token = _session(monkeypatch)

    async def fake_latest(exchange, *, max_lookback_days=10):
        assert exchange == "bse"
        return "tejhq_eod", date(2026, 11, 20), [
            {"symbol": "BSE_A", "series": "A", "close": 110, "prev_close": 100, "volume": 1000},
            {"symbol": "BSE_B", "series": "B", "close": 90, "prev_close": 100, "volume": 2000},
            {"symbol": "BSE_T", "series": "T", "close": 100, "prev_close": 100, "volume": 3000},
            {"symbol": "NOT_EQUITY", "series": "Z", "close": 120, "prev_close": 100, "volume": 9000},
        ]

    monkeypatch.setattr(tejhq_service, "fetch_latest_snapshot", fake_latest)
    response = TestClient(api.app).get(
        "/api/v1/app/market-data/eod/latest/bse",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    summary = response.json()["summary"]
    assert summary["equity_series_rows"] == 3
    assert (summary["advances"], summary["declines"], summary["unchanged"]) == (1, 1, 1)
    assert "NOT_EQUITY" not in response.text
