from datetime import date

import asyncio

import pytest
from fastapi.testclient import TestClient

from rovexquant.ai import gateway
from rovexquant.api.main import app
from rovexquant.auth import issue_app_session
from rovexquant.market_data.tejhq import TejHqDailyBar


class FakeResponse:
    status_code = 200
    def json(self):
        return {
            "choices": [{"message": {"content": "The EOD evidence is mixed; validate with out-of-sample tests."}}],
            "usage": {"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70},
        }


class FakeAsyncClient:
    requests = []
    def __init__(self, *args, **kwargs):
        pass
    async def __aenter__(self):
        return self
    async def __aexit__(self, *args):
        return False
    async def post(self, url, headers, json):
        self.requests.append({"url": url, "headers": headers, "json": json})
        return FakeResponse()


@pytest.fixture
def mobile_client(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "correct-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "x" * 48)
    monkeypatch.setenv("GROQ_API_KEY", "groq-server-secret")
    monkeypatch.setattr(gateway.httpx, "AsyncClient", FakeAsyncClient)
    FakeAsyncClient.requests = []
    token, _ = issue_app_session("quant-test", "correct-password")
    return TestClient(app), {"Authorization": f"Bearer {token}"}


def test_mobile_ai_provider_catalogue_requires_app_session(mobile_client):
    client, headers = mobile_client
    assert client.get("/api/v1/app/ai/providers").status_code == 401
    response = client.get("/api/v1/app/ai/providers", headers=headers)
    assert response.status_code == 200
    assert any(item["provider_id"] == "groq" and item["configured"] for item in response.json()["providers"])
    assert "groq-server-secret" not in response.text
    assert "QUANT_AI_GATEWAY_TOKEN" not in response.text


def test_mobile_chat_uses_app_session_and_server_provider_secret(mobile_client):
    client, headers = mobile_client
    response = client.post("/api/v1/app/ai/chat", headers=headers, json={
        "provider_id": "groq",
        "model": "test-model",
        "messages": [{"role": "user", "content": "Explain the supplied evidence."}],
    })
    assert response.status_code == 200
    assert response.json()["content"].startswith("The EOD evidence")
    assert FakeAsyncClient.requests[0]["headers"]["Authorization"] == "Bearer groq-server-secret"
    assert FakeAsyncClient.requests[0]["json"]["messages"][0]["role"] == "system"
    assert "groq-server-secret" not in response.text


def test_strategy_evidence_summary_does_not_claim_live_data(monkeypatch):
    from rovexquant.api.routers import ai as ai_router

    bars = [
        TejHqDailyBar(
            date=date.fromordinal(date(2026, 1, 1).toordinal() + i),
            open=100.0 + i, high=102.0 + i, low=99.0 + i,
            close=101.0 + i, volume=1000 + i,
            adjusted_close=101.0 + i, adjustment_factor=1.0,
        )
        for i in range(70)
    ]
    async def fake_fetch(*args, **kwargs):
        assert kwargs["adjusted"] is True
        return "tejhq_eod", bars
    async def fake_complete(**kwargs):
        assert "END_OF_DAY" in kwargs["messages"][1]["content"]
        return {"provider_id": "groq", "model": "test-model", "content": "EOD analysis only.", "usage": {}, "fallback_used": False}
    monkeypatch.setattr(ai_router.tejhq_service, "fetch_ohlcv", fake_fetch)
    monkeypatch.setattr(ai_router, "complete_chat", fake_complete)
    from rovexquant.api.routers.ai import AIStrategyBriefRequest, app_ai_strategy_brief
    request = AIStrategyBriefRequest(
        provider_id="groq", model="test-model", exchange="NSE", symbol="RELIANCE",
        from_date=date(2026, 1, 1), to_date=date(2026, 4, 1),
        strategy_context="Review a Donchian breakout idea.",
    )
    result = asyncio.run(app_ai_strategy_brief(request))
    assert result["data_mode"] == "END_OF_DAY_ADJUSTED"
    assert result["bar_count"] == 70
    assert result["ai"]["content"] == "EOD analysis only."
    assert "not suitable for intraday" in result["limitations"][0]


def test_strategy_brief_rejects_short_history(mobile_client, monkeypatch):
    client, headers = mobile_client
    from rovexquant.api.routers import ai as ai_router
    async def short_history(*args, **kwargs):
        return "tejhq_eod", []
    monkeypatch.setattr(ai_router.tejhq_service, "fetch_ohlcv", short_history)
    response = client.post("/api/v1/app/ai/strategy-brief", headers=headers, json={
        "provider_id": "groq", "model": "test-model", "symbol": "RELIANCE",
        "from_date": "2026-01-01", "to_date": "2026-04-01",
        "strategy_context": "Review trend continuation.",
    })
    assert response.status_code == 422



def test_corporate_action_factor_scales_all_ohlc_and_reconciles_close():
    from rovexquant.api.routers.ai import _apply_corporate_action_adjustment
    raw = TejHqDailyBar(
        date=date(2026, 1, 1),
        open=200.0,
        high=210.0,
        low=190.0,
        close=205.0,
        adjusted_close=102.5,
        adjustment_factor=0.5,
        volume=2000,
    )
    adjusted = _apply_corporate_action_adjustment([raw])[0]
    assert adjusted.open == 100.0
    assert adjusted.high == 105.0
    assert adjusted.low == 95.0
    assert adjusted.close == 102.5
    assert adjusted.volume == 2000


def test_corporate_action_factor_mismatch_fails_closed():
    from rovexquant.api.routers.ai import _apply_corporate_action_adjustment
    from rovexquant.market_data.tejhq_service import TejHqError
    raw = TejHqDailyBar(
        date=date(2026, 1, 1),
        open=200.0,
        high=210.0,
        low=190.0,
        close=205.0,
        adjusted_close=110.0,
        adjustment_factor=0.5,
        volume=2000,
    )
    with pytest.raises(TejHqError):
        _apply_corporate_action_adjustment([raw])
