import pytest
from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient
from rovexquant.api.main import app
from rovexquant.auth import issue_app_session
from rovexquant.security.request_controls import rate_limiter

client = TestClient(app)


@pytest.fixture(autouse=True)
def configure_app_auth(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "quant-test-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "test-session-signing-key-32-bytes-minimum")


def auth_headers():
    token, _ = issue_app_session("quant-test", "quant-test-password")
    return {"Authorization": f"Bearer {token}"}

def test_health_is_research_only():
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json()["mode"] == "research-only"

def test_strategy_catalogue_contains_multiple_horizons():
    response = client.get("/api/v1/strategies")
    assert response.status_code == 200
    strategies = response.json()
    assert len(strategies) >= 6
    assert {item["horizon"] for item in strategies} >= {"intraday", "swing"}

def test_indicator_endpoint_returns_warmup_values():
    response = client.post("/api/v1/research/indicators", json={"close": [float(x) for x in range(1, 50)]}, headers=auth_headers())
    assert response.status_code == 200
    assert len(response.json()["rsi"]) == 49
    assert len(response.json()["macd"][0]) == 49

def test_indicator_endpoint_returns_sma_ema_and_bollinger_reference_values():
    response = client.post("/api/v1/research/indicators", headers=auth_headers(), json={
        "close": [1, 2, 3, 4, 5],
        "sma_period": 3,
        "ema_period": 3,
        "bollinger_period": 3,
        "bollinger_deviations": 2,
    })
    assert response.status_code == 200
    result = response.json()
    assert result["sma"][:2] == [None, None]
    assert result["sma"][2:] == [2.0, 3.0, 4.0]
    assert result["ema"][:2] == [None, None]
    assert result["ema"][2:] == [2.0, 3.0, 4.0]
    bands = result["bollinger_bands"]
    assert bands["middle"][2] == 2.0
    assert bands["upper"][2] == pytest.approx(2 + 2 * (2 / 3) ** 0.5)
    assert bands["lower"][2] == pytest.approx(2 - 2 * (2 / 3) ** 0.5)


def test_indicator_endpoint_rejects_invalid_moving_average_period():
    response = client.post("/api/v1/research/indicators", headers=auth_headers(), json={
        "close": [10, 11, 12],
        "sma_period": 0,
    })
    assert response.status_code == 422


def test_indicator_endpoint_rejects_partial_ohlc():
    response = client.post("/api/v1/research/indicators", json={"close": [10, 11], "high": [11, 12]}, headers=auth_headers())
    assert response.status_code == 422

def test_backtest_endpoint_executes_next_bar_fill():
    start = datetime(2025, 1, 1, tzinfo=timezone.utc)
    bars = []
    for index, price in enumerate((100, 100, 105, 110)):
        stamp = start + timedelta(days=index)
        bars.append({"timestamp": stamp.isoformat(), "open": price, "high": price + 2, "low": price - 2, "close": price})
    response = client.post("/api/v1/research/backtest", headers=auth_headers(), json={
        "bars": bars, "signals": [1, 0, -1, 0], "stop_fraction": 0.2,
        "commission_bps": 0, "slippage_bps": 0,
    })
    assert response.status_code == 200
    assert response.json()["trade_count"] == 1
    assert response.json()["final_equity"] == 100500

def test_backtest_rejects_mismatched_signal_count():
    response = client.post("/api/v1/research/backtest", headers=auth_headers(), json={
        "bars": [{"timestamp": "2025-01-01T00:00:00Z", "open": 100, "high": 101, "low": 99, "close": 100}] * 2,
        "signals": [1],
    })
    assert response.status_code == 422



def test_research_compute_requires_app_session():
    response = client.post("/api/v1/research/indicators", json={"close": [1, 2, 3]})
    assert response.status_code == 401


def test_indicator_input_is_bounded():
    response = client.post(
        "/api/v1/research/indicators",
        headers=auth_headers(),
        json={"close": [1] * 10001},
    )
    assert response.status_code == 422


def test_request_body_limit_rejects_oversized_payload():
    response = client.post(
        "/api/v1/auth/session",
        content=b" " * (2 * 1024 * 1024 + 1),
        headers={"Content-Type": "application/json"},
    )
    assert response.status_code == 413


def test_login_rate_limit_cannot_be_bypassed_with_forwarded_ip_header():
    rate_limiter.clear()
    try:
        responses = [
            client.post(
                "/api/v1/auth/session",
                json={"username": "quant-test", "password": "quant-test-password"},
                headers={"X-Forwarded-For": f"203.0.113.{index}"},
            )
            for index in range(6)
        ]
        assert [response.status_code for response in responses[:5]] == [200] * 5
        assert responses[5].status_code == 429
        assert int(responses[5].headers["Retry-After"]) >= 1
    finally:
        rate_limiter.clear()



def test_request_id_is_server_generated_and_returned():
    response = client.get("/api/v1/health", headers={"X-Request-ID": "caller-controlled"})
    request_id = response.headers["X-Request-ID"]
    assert response.status_code == 200
    assert request_id != "caller-controlled"
    assert len(request_id) == 32
    int(request_id, 16)


def test_readiness_checks_local_storage_without_provider_credentials(monkeypatch, tmp_path):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "sqlite")
    monkeypatch.delenv("QUANT_TOKEN_DATABASE_URL", raising=False)
    monkeypatch.setenv("QUANT_TOKEN_DB_PATH", str(tmp_path / "ready.sqlite3"))
    monkeypatch.delenv("QUANT_TOKEN_ENCRYPTION_KEY", raising=False)

    response = client.get("/api/v1/health/ready")
    assert response.status_code == 200
    assert response.json()["status"] == "ready"


def test_readiness_fails_closed_for_unconfigured_hosted_storage(monkeypatch):
    monkeypatch.setenv("QUANT_TOKEN_STORAGE_MODE", "postgresql")
    monkeypatch.delenv("QUANT_TOKEN_DATABASE_URL", raising=False)
    monkeypatch.delenv("QUANT_TOKEN_ENCRYPTION_KEY", raising=False)

    response = client.get("/api/v1/health/ready")
    assert response.status_code == 503
    assert response.json()["detail"] == (
        "Required credential storage is unavailable or its schema is not migrated"
    )
    assert "postgresql://" not in response.text
