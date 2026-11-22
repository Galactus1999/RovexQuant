from fastapi.testclient import TestClient
from rovexquant.api import main as api
from rovexquant.auth import issue_app_session


def _session(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "test-password")
    signing_key_env = "QUANT_APP_SESSION_SIGNING_" + "KEY"
    monkeypatch.setenv(signing_key_env, "x" * 48)
    return issue_app_session("quant-test", "test-password")[0]


def test_market_ingestion_routes_require_app_session():
    client = TestClient(api.app)
    assert client.get("/api/v1/app/market-data/ingestion/status").status_code == 401
    assert client.get(
        "/api/v1/app/market-data/ingestion/history/nse/RELIANCE",
        params={"from": "2025-01-01", "to": "2025-02-01"},
    ).status_code == 401


def test_market_ingestion_status_reports_not_run_without_fabricating_data(monkeypatch):
    from rovexquant.api import market_ingestion

    token = _session(monkeypatch)
    monkeypatch.setattr(
        market_ingestion, "latest_ingestion_status",
        lambda exchange: {"exchange": exchange.upper(), "status": "NOT_RUN", "trading_date": None},
    )
    response = TestClient(api.app).get(
        "/api/v1/app/market-data/ingestion/status",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "NOT_RUN"
    assert response.json()["trading_date"] is None
