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
        api.TejHqEodProvider, "from_environment", classmethod(lambda cls: provider)
    )
    response = TestClient(api.app).get(
        "/api/v1/app/research/market-data/ohlcv/NSE/RELIANCE",
        params={"from": "2025-05-28", "to": "2025-05-28", "adjusted": "true"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 503
    assert "optional free API key" in response.json()["detail"]
