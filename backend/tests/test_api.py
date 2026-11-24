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

def test_indicator_endpoint_returns_aligned_ohlc_indicator_arrays_and_warmups():
    closes = [100.0 + index * 0.5 for index in range(80)]
    highs = [value + 1.0 for value in closes]
    lows = [value - 1.0 for value in closes]
    response = client.post(
        "/api/v1/research/indicators",
        headers=auth_headers(),
        json={"close": closes, "high": highs, "low": lows},
    )
    assert response.status_code == 200
    result = response.json()
    assert len(result["sma"]) == len(closes)
    assert len(result["ema"]) == len(closes)
    assert len(result["rsi"]) == len(closes)
    assert len(result["macd"]) == 3
    assert all(len(series) == len(closes) for series in result["macd"])
    assert all(len(series) == len(closes) for series in result["bollinger_bands"].values())
    assert len(result["atr"]) == len(closes)
    assert len(result["adx"]) == len(closes)
    assert result["rsi"][:14] == [None] * 14
    assert result["rsi"][14] is not None
    assert result["atr"][:14] == [None] * 14
    assert result["atr"][14] is not None
    assert result["adx"][:27] == [None] * 27
    assert result["adx"][27] is not None
    assert result["macd"][0][:25] == [None] * 25
    assert result["macd"][0][25] is not None
    assert result["macd"][1][:33] == [None] * 33
    assert result["macd"][1][33] is not None


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
    indicators = client.post("/api/v1/research/indicators", json={"close": [1, 2, 3]})
    backtest = client.post("/api/v1/research/backtest", json={
        "bars": [
            {"timestamp": "2025-01-01T00:00:00Z", "open": 100, "high": 101, "low": 99, "close": 100},
            {"timestamp": "2025-01-02T00:00:00Z", "open": 101, "high": 102, "low": 100, "close": 101},
        ],
        "signals": [1, 0],
    })
    assert indicators.status_code == 401
    assert backtest.status_code == 401


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
        assert responses[5].json()["error"]["code"] == "rate_limited"
        assert responses[5].json()["request_id"] == responses[5].headers["X-Request-ID"]
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


def test_validation_errors_are_structured_and_never_echo_password_input():
    secret = "DO_NOT_ECHO_THIS_PASSWORD_" + ("x" * 300)
    response = client.post(
        "/api/v1/auth/session",
        json={"username": "quant-test", "password": secret},
    )
    assert response.status_code == 422
    body = response.json()
    assert body["error"]["code"] == "validation_error"
    assert body["request_id"] == response.headers["X-Request-ID"]
    assert secret not in response.text
    assert all("input" not in item for item in body["detail"])


def test_auth_failure_uses_structured_error_contract():
    response = client.get("/api/v1/app/market/providers")
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"
    assert response.json()["request_id"] == response.headers["X-Request-ID"]


def test_request_logs_are_structured_and_redact_headers_query_and_body(caplog):
    from rovexquant.security.request_context import request_metrics

    request_metrics.clear()
    caplog.set_level("INFO", logger="rovexquant.request")
    secrets = (
        "AUTHORIZATION_SECRET_7f6e",
        "ADMIN_HEADER_SECRET_8a9b",
        "QUERY_SECRET_1c2d",
    )
    response = client.get(
        "/api/v1/health?access_token=" + secrets[2],
        headers={
            "Authorization": "Bearer " + secrets[0],
            "X-Quant-Admin-Key": secrets[1],
        },
    )
    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == response.json().get("request_id", response.headers["X-Request-ID"])
    matching = [record.getMessage() for record in caplog.records if record.name == "rovexquant.request"]
    assert matching
    event = __import__("json").loads(matching[-1])
    assert event["event"] == "http_request"
    assert event["route"] == "/api/v1/health"
    assert event["status_code"] == 200
    assert event["request_id"] == response.headers["X-Request-ID"]
    assert event["duration_ms"] >= 0
    combined = "\n".join(matching)
    assert all(secret not in combined for secret in secrets)
    assert request_metrics.snapshot()["requests"]


def test_process_metrics_are_admin_only_and_report_real_http_requests(monkeypatch):
    from rovexquant.security.request_context import request_metrics

    request_metrics.clear()
    monkeypatch.setenv("QUANT_ADMIN_GATEWAY_TOKEN", "admin-test-token-long-enough")
    assert client.get("/api/v1/health/metrics").status_code == 401
    response = client.get(
        "/api/v1/health/metrics",
        headers={"X-Quant-Admin-Key": "admin-test-token-long-enough"},
    )
    assert response.status_code == 200
    assert response.json()["scope"] == "single_process"
    assert any(item["route"] == "/api/v1/health/metrics" for item in response.json()["requests"])


def test_body_limit_returns_structured_error_and_correlation_id():
    response = client.post(
        "/api/v1/auth/session",
        content=b" " * (2 * 1024 * 1024 + 1),
        headers={"Content-Type": "application/json"},
    )
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "request_too_large"
    assert response.json()["request_id"] == response.headers["X-Request-ID"]


def test_openapi_exposes_versioned_research_contract_and_bearer_auth():
    response = client.get("/openapi.json")
    assert response.status_code == 200
    schema = response.json()
    assert schema["info"]["version"] == "0.5.0"
    security = schema["components"]["securitySchemes"]["QuantAppBearer"]
    assert security["type"] == "http"
    assert security["scheme"] == "bearer"
    for route in ("/api/v1/research/indicators", "/api/v1/research/backtest"):
        operation = schema["paths"][route]["post"]
        assert operation["security"] == [{"QuantAppBearer": []}]
        assert "requestBody" in operation


def test_unhandled_server_error_returns_safe_correlated_envelope():
    from rovexquant.api.main import app

    secret = "UNHANDLED_EXCEPTION_SECRET_MUST_NOT_LEAK"

    def explode():
        raise RuntimeError(secret)

    app.add_api_route("/_test/unhandled-error", explode, methods=["GET"])
    try:
        response = TestClient(app, raise_server_exceptions=False).get("/_test/unhandled-error")
        assert response.status_code == 500
        assert response.json()["error"]["code"] == "internal_error"
        assert response.json()["request_id"] == response.headers["X-Request-ID"]
        assert secret not in response.text
    finally:
        app.router.routes[:] = [
            route for route in app.router.routes
            if getattr(route, "path", None) != "/_test/unhandled-error"
        ]


def test_api_route_table_has_no_duplicate_http_method_owners():
    from rovexquant.api.main import app

    owners = []
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None)
        if path and methods:
            for method in methods:
                owners.append((method, path))
    assert len(owners) == len(set(owners))


def test_w3c_trace_context_is_extracted_for_http_span_and_safe_log(caplog):
    caplog.set_level("INFO", logger="rovexquant.request")
    trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
    response = client.get(
        "/api/v1/health",
        headers={
            "traceparent": f"00-{trace_id}-00f067aa0ba902b7-01",
            "X-Request-ID": "caller-controlled",
        },
    )
    assert response.status_code == 200
    assert response.headers["X-Request-ID"] != "caller-controlled"
    matching = [
        record.getMessage()
        for record in caplog.records
        if record.name == "rovexquant.request"
    ]
    assert matching
    event = __import__("json").loads(matching[-1])
    assert event["trace_id"] == trace_id
    assert event["request_id"] == response.headers["X-Request-ID"]
