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


def test_bulk_market_history_requires_app_session():
    response = TestClient(api.app).post(
        "/api/v1/app/market-data/ingestion/history/bulk",
        json={
            "exchange": "nse",
            "targets": [{"symbol": "RELIANCE", "from_date": "2026-11-17"}],
            "to_date": "2026-11-27",
        },
    )
    assert response.status_code == 401


def test_bulk_market_history_returns_bounded_persisted_rows(monkeypatch):
    from rovexquant.api import market_ingestion

    token = _session(monkeypatch)
    calls = []
    rows = [{
        "symbol": "RELIANCE", "series": "EQ", "date": "2026-11-26",
        "open": 100.0, "high": 105.0, "low": 99.0, "close": 104.0,
        "volume": 1000, "provider": "tejhq_eod", "data_mode": "END_OF_DAY",
    }]
    monkeypatch.setattr(
        market_ingestion, "bulk_symbol_history",
        lambda exchange, targets, to_date, limit_per_symbol: (
            calls.append((exchange, targets, to_date, limit_per_symbol)) or rows
        ),
    )
    response = TestClient(api.app).post(
        "/api/v1/app/market-data/ingestion/history/bulk",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "exchange": "nse",
            "targets": [{"symbol": "RELIANCE", "from_date": "2026-11-17"}],
            "to_date": "2026-11-27",
            "limit_per_symbol": 30,
        },
    )
    assert response.status_code == 200
    assert response.json()["count"] == 1
    assert response.json()["symbols_returned"] == 1
    assert response.json()["bars"][0]["provider"] == "tejhq_eod"
    assert calls[0][0] == "nse"
    assert calls[0][1] == [{"symbol": "RELIANCE", "from_date": __import__("datetime").date(2026, 11, 17)}]
    assert calls[0][3] == 30


def test_bulk_market_history_rejects_more_than_two_thousand_targets(monkeypatch):
    token = _session(monkeypatch)
    targets = [{"symbol": f"STK{i}", "from_date": "2026-11-17"} for i in range(2001)]
    response = TestClient(api.app).post(
        "/api/v1/app/market-data/ingestion/history/bulk",
        headers={"Authorization": f"Bearer {token}"},
        json={"exchange": "nse", "targets": targets, "to_date": "2026-11-27"},
    )
    assert response.status_code == 422


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
