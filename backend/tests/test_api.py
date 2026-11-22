from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient
from rovexquant.api.main import app

client = TestClient(app)

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
    response = client.post("/api/v1/research/indicators", json={"close": [float(x) for x in range(1, 50)]})
    assert response.status_code == 200
    assert len(response.json()["rsi"]) == 49
    assert len(response.json()["macd"][0]) == 49

def test_indicator_endpoint_returns_sma_ema_and_bollinger_reference_values():
    response = client.post("/api/v1/research/indicators", json={
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
    assert result["ema"][2:] == [2.0, 3.0, 4.25]
    bands = result["bollinger_bands"]
    assert bands["middle"][2] == 2.0
    assert bands["upper"][2] == pytest.approx(2 + 2 * (2 / 3) ** 0.5)
    assert bands["lower"][2] == pytest.approx(2 - 2 * (2 / 3) ** 0.5)


def test_indicator_endpoint_rejects_invalid_moving_average_period():
    response = client.post("/api/v1/research/indicators", json={
        "close": [10, 11, 12],
        "sma_period": 0,
    })
    assert response.status_code == 422


def test_indicator_endpoint_rejects_partial_ohlc():
    response = client.post("/api/v1/research/indicators", json={"close": [10, 11], "high": [11, 12]})
    assert response.status_code == 422

def test_backtest_endpoint_executes_next_bar_fill():
    start = datetime(2025, 1, 1, tzinfo=timezone.utc)
    bars = []
    for index, price in enumerate((100, 100, 105, 110)):
        stamp = start + timedelta(days=index)
        bars.append({"timestamp": stamp.isoformat(), "open": price, "high": price + 2, "low": price - 2, "close": price})
    response = client.post("/api/v1/research/backtest", json={
        "bars": bars, "signals": [1, 0, -1, 0], "stop_fraction": 0.2,
        "commission_bps": 0, "slippage_bps": 0,
    })
    assert response.status_code == 200
    assert response.json()["trade_count"] == 1
    assert response.json()["final_equity"] == 100500

def test_backtest_rejects_mismatched_signal_count():
    response = client.post("/api/v1/research/backtest", json={
        "bars": [{"timestamp": "2025-01-01T00:00:00Z", "open": 100, "high": 101, "low": 99, "close": 100}] * 2,
        "signals": [1],
    })
    assert response.status_code == 422
