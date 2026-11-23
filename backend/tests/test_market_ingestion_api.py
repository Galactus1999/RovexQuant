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
    assert client.get("/api/v1/app/market-data/ingestion/snapshot/nse").status_code == 401
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


def test_market_ingestion_snapshot_returns_postgres_data_only(monkeypatch):
    from rovexquant.api import market_ingestion

    token = _session(monkeypatch)
    fixture = {
        "provider": "tejhq_eod",
        "data_mode": "END_OF_DAY",
        "data_origin": "POSTGRESQL",
        "exchange": "NSE",
        "market_scope": "NSE_EQ",
        "trading_date": "2026-11-20",
        "quality_status": "PROVISIONAL",
        "summary": {"total_rows": 3, "priced_symbols": 3, "advances": 1, "declines": 1},
    }
    monkeypatch.setattr(
        market_ingestion, "latest_persisted_snapshot",
        lambda exchange, limit: {**fixture, "exchange": exchange.upper(), "limit": limit},
    )
    response = TestClient(api.app).get(
        "/api/v1/app/market-data/ingestion/snapshot/nse",
        params={"limit": 10},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    assert response.json()["data_origin"] == "POSTGRESQL"
    assert response.json()["data_mode"] == "END_OF_DAY"
    assert response.json()["trading_date"] == "2026-11-20"


def test_market_ingestion_collection_requires_app_session():
    client = TestClient(api.app)
    assert client.post("/api/v1/app/market-data/ingestion/collect/nse").status_code == 401


def test_market_ingestion_collection_persists_queue_request(monkeypatch):
    from rovexquant.api import market_ingestion

    token = _session(monkeypatch)
    queued = []
    monkeypatch.setattr(
        market_ingestion, "enqueue_ingestion_run",
        lambda exchange, force=False: (queued.append((exchange, force)) or ("run-123", True, "QUEUED")),
    )
    response = TestClient(api.app).post(
        "/api/v1/app/market-data/ingestion/collect/nse?force=true",
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 202
    assert response.json()["started"] is True
    assert response.json()["status"] == "QUEUED"
    assert response.json()["run_id"] == "run-123"
    assert queued == [("nse", True)]


def test_market_ingestion_collection_joins_existing_durable_job(monkeypatch):
    from rovexquant.api import market_ingestion

    token = _session(monkeypatch)
    monkeypatch.setattr(
        market_ingestion, "enqueue_ingestion_run",
        lambda exchange, force=False: ("existing-run", False, "RUNNING"),
    )
    response = TestClient(api.app).post(
        "/api/v1/app/market-data/ingestion/collect/nse",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 202
    assert response.json()["started"] is False
    assert response.json()["status"] == "RUNNING"
