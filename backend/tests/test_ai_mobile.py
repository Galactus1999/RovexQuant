from datetime import date

import asyncio

import pytest
from fastapi.testclient import TestClient

from rovexquant.ai import gateway
from rovexquant.api.main import app
from rovexquant.auth import issue_app_session
from rovexquant.market_data.tejhq import TejHqDailyBar
from rovexquant.market_data.eod_store import EodStoreError


class FakeResponse:
    status_code = 200
    def json(self):
        return {
            "choices": [{"message": {"content": "The EOD evidence is mixed; validate with out-of-sample tests."}}],
            "usage": {"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70, "cost": 0.0004},
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
    monkeypatch.setenv("GROQ_FALLBACK_MODEL", "groq-fallback-test-model")
    monkeypatch.setattr(gateway.httpx, "AsyncClient", FakeAsyncClient)
    FakeAsyncClient.requests = []
    token, _ = issue_app_session("quant-test", "correct-password")
    return TestClient(app), {"Authorization": f"Bearer {token}"}


def test_mobile_ai_provider_catalogue_requires_app_session(mobile_client):
    client, headers = mobile_client
    assert client.get("/api/v1/app/ai/providers").status_code == 401
    response = client.get("/api/v1/app/ai/providers", headers=headers)
    assert response.status_code == 200
    groq = next(item for item in response.json()["providers"] if item["provider_id"] == "groq")
    assert groq["configured"] is True
    assert groq["fallback_model_configured"] is True
    assert "groq-fallback-test-model" not in response.text
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
    assert response.json()["archive_status"] in {"PERSISTED", "UNAVAILABLE"}
    assert response.json()["task_type"] == "chat"
    assert response.json()["cost_usd"] == 0.0004
    assert response.json()["cost_status"] == "PROVIDER_REPORTED"


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



def test_adjusted_eod_data_is_returned_when_ai_provider_is_not_configured(monkeypatch):
    from rovexquant.api.routers import ai as ai_router
    bars = [
        TejHqDailyBar(
            date=date.fromordinal(date(2026, 1, 1).toordinal() + i),
            open=100.0 + i, high=102.0 + i, low=99.0 + i,
            close=101.0 + i, volume=1000 + i,
            adjusted_close=101.0 + i, adjustment_factor=1.0,
        )
        for i in range(40)
    ]
    async def fake_fetch(*args, **kwargs):
        assert kwargs["adjusted"] is True
        return "tejhq_eod", bars
    async def unexpected_ai_call(**kwargs):
        raise AssertionError("AI must not be called without a configured provider/model")
    monkeypatch.setattr(ai_router.tejhq_service, "fetch_ohlcv", fake_fetch)
    monkeypatch.setattr(ai_router, "complete_chat", unexpected_ai_call)
    request = ai_router.AIStrategyBriefRequest(
        provider_id=None, model=None, exchange="NSE", symbol="RELIANCE",
        from_date=date(2026, 1, 1), to_date=date(2026, 3, 1),
        strategy_context="Review a swing strategy.",
    )
    result = asyncio.run(ai_router.app_ai_strategy_brief(request))
    assert result["bar_count"] == 40
    assert result["data_mode"] == "END_OF_DAY_ADJUSTED"
    assert result["ai"] is None
    assert "Configure an AI provider/model" in result["ai_error"]


def test_adjusted_eod_data_survives_ai_provider_failure(monkeypatch):
    from rovexquant.api.routers import ai as ai_router
    bars = [
        TejHqDailyBar(
            date=date.fromordinal(date(2026, 1, 1).toordinal() + i),
            open=100.0 + i, high=102.0 + i, low=99.0 + i,
            close=101.0 + i, volume=1000 + i,
            adjusted_close=101.0 + i, adjustment_factor=1.0,
        )
        for i in range(40)
    ]
    async def fake_fetch(*args, **kwargs):
        return "tejhq_eod", bars
    async def failed_ai(**kwargs):
        raise ai_router.GatewayError("Provider unavailable", 503)
    monkeypatch.setattr(ai_router.tejhq_service, "fetch_ohlcv", fake_fetch)
    monkeypatch.setattr(ai_router, "complete_chat", failed_ai)
    request = ai_router.AIStrategyBriefRequest(
        provider_id="groq", model="test-model", exchange="NSE", symbol="RELIANCE",
        from_date=date(2026, 1, 1), to_date=date(2026, 3, 1),
        strategy_context="Review a swing strategy.",
    )
    result = asyncio.run(ai_router.app_ai_strategy_brief(request))
    assert result["bar_count"] == 40
    assert result["ai"] is None
    assert "Provider unavailable" in result["ai_error"]


def test_app_ai_provider_credential_management_requires_session_and_never_echoes(
    mobile_client, monkeypatch
):
    client, headers = mobile_client
    from rovexquant.api.routers import ai as ai_router

    secret = "owner-groq-api-key-secret-value-123456"
    stored = []
    monkeypatch.setattr(
        ai_router, "store_ai_provider_api_key",
        lambda provider_id, api_key: stored.append((provider_id, api_key)),
    )
    unauthorized = client.put(
        "/api/v1/app/ai/providers/groq/credential",
        json={"api_key": secret},
    )
    assert unauthorized.status_code == 401

    response = client.put(
        "/api/v1/app/ai/providers/groq/credential",
        headers=headers,
        json={"api_key": secret},
    )
    assert response.status_code == 200
    assert stored == [("groq", secret)]
    assert secret not in response.text
    assert response.json()["credential_storage"] == "encrypted_server_side"

    monkeypatch.setattr(ai_router, "delete_ai_provider_api_key", lambda provider_id: provider_id == "groq")
    deleted = client.delete(
        "/api/v1/app/ai/providers/groq/credential",
        headers=headers,
    )
    assert deleted.status_code == 200
    assert deleted.json() == {"provider_id": "groq", "deleted": True}


def test_app_ai_provider_credential_rejects_unknown_provider(mobile_client):
    client, headers = mobile_client
    response = client.put(
        "/api/v1/app/ai/providers/not-a-provider/credential",
        headers=headers,
        json={"api_key": "owner-api-key-secret-value-123456789"},
    )
    assert response.status_code == 404


def test_app_upstox_credential_setup_is_authenticated_and_never_echoes_token(
    mobile_client, monkeypatch
):
    client, headers = mobile_client
    from rovexquant.api.routers import integrations

    token = "owner-upstox-analytics-token-secret-123456789"
    stored = []
    monkeypatch.setattr(
        integrations, "store_upstox_analytics_token",
        lambda value: stored.append(value),
    )
    unauthorized = client.put(
        "/api/v1/app/integrations/upstox/analytics-token",
        json={"token": token},
    )
    assert unauthorized.status_code == 401

    response = client.put(
        "/api/v1/app/integrations/upstox/analytics-token",
        headers=headers,
        json={"token": token},
    )
    assert response.status_code == 200
    assert stored == [token]
    assert token not in response.text
    assert response.json()["credential_storage"] == "encrypted_server_side"

    monkeypatch.setattr(integrations, "upstox_analytics_status", lambda: {
        "connected": True,
        "credential_type": "analytics_read_only",
        "credential_storage": "encrypted_server_side",
        "expires_at": 4102444800,
    })
    status = client.get("/api/v1/app/integrations/upstox/status", headers=headers)
    assert status.status_code == 200
    assert status.json()["configured"] is True
    assert status.json()["connection_verified"] is False
    assert token not in status.text

    monkeypatch.setattr(integrations, "delete_upstox_analytics_token", lambda: True)
    deleted = client.delete(
        "/api/v1/app/integrations/upstox/analytics-token",
        headers=headers,
    )
    assert deleted.status_code == 200
    assert deleted.json()["deleted"] is True


def test_short_provider_secrets_are_not_reflected_in_validation_errors(mobile_client):
    client, headers = mobile_client
    ai_secret = "short-ai-key-secret"
    upstox_secret = "short-upstox-secret"

    ai_response = client.put(
        "/api/v1/app/ai/providers/groq/credential",
        headers=headers,
        json={"api_key": ai_secret},
    )
    assert ai_response.status_code == 422
    assert ai_secret not in ai_response.text

    upstox_response = client.put(
        "/api/v1/app/integrations/upstox/analytics-token",
        headers=headers,
        json={"token": upstox_secret},
    )
    assert upstox_response.status_code == 422
    assert upstox_secret not in upstox_response.text


def test_task_specific_routes_are_validated_and_do_not_expose_credentials(monkeypatch):
    monkeypatch.setenv("QUANT_AI_TASK_ROUTES", '{"research":{"provider_id":"groq","model":"research-model","fallback_providers":["openrouter"]},"claim_verification":{"provider_id":"openai","model":"verifier-model"}}')
    routes = gateway.configured_task_routes()
    assert routes["research"]["provider_id"] == "groq"
    assert routes["research"]["model"] == "research-model"
    assert "api_key" not in str(routes)
    provider, model, fallbacks = gateway.resolve_task_route("research", None, None, None)
    assert (provider, model, fallbacks) == ("groq", "research-model", ["openrouter"])


def test_task_route_rejects_unknown_task_and_malformed_provider(monkeypatch):
    with pytest.raises(gateway.GatewayError):
        gateway.resolve_task_route("unknown", None, None, None)
    monkeypatch.setenv("QUANT_AI_TASK_ROUTES", '{"research":{"provider_id":"unknown","model":"x"}}')
    with pytest.raises(gateway.GatewayError):
        gateway.configured_task_routes()


def test_research_archive_persists_bounded_reproducible_run(monkeypatch):
    from rovexquant.ai import research_archive

    class FakeConnection:
        def __init__(self):
            self.query = None
            self.params = None
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def execute(self, query, params):
            self.query = query
            self.params = params

    fake = FakeConnection()
    monkeypatch.setattr(research_archive, "connect", lambda: fake)
    run_id = research_archive.persist_ai_research_run(
        task_type="research",
        provider_id="groq",
        model="test-model",
        credential_id="app",
        request_payload={"messages": [{"role": "user", "content": "Check this EOD evidence."}]},
        response_payload={"content": "Evidence is mixed."},
        usage={"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70},
        cost_status="NOT_TRACKED",
        source_refs=[{"provider": "tejhq_eod", "symbol": "RELIANCE"}],
    )
    assert len(run_id) == 36
    assert "INSERT INTO ai_research_runs" in fake.query
    assert "'SUCCEEDED'" in fake.query
    assert len(fake.params[5]) == 64
    assert "groq-server-secret" not in str(fake.params)


def test_research_archive_rejects_oversized_payload():
    from rovexquant.ai.research_archive import persist_ai_research_run

    with pytest.raises(EodStoreError):
        persist_ai_research_run(
            task_type="chat",
            provider_id="groq",
            model="test-model",
            credential_id=None,
            request_payload={"text": "x" * (129 * 1024)},
            response_payload={"content": "ok"},
        )


def test_ai_research_history_endpoints_require_app_session(mobile_client, monkeypatch):
    client, headers = mobile_client
    assert client.get("/api/v1/app/ai/research/runs").status_code == 401
    from rovexquant.api.routers import ai as ai_router
    monkeypatch.setattr(ai_router, "list_ai_research_runs", lambda **kwargs: {"runs": [], "count": 0})
    response = client.get("/api/v1/app/ai/research/runs", headers=headers)
    assert response.status_code == 200
    assert response.json() == {"runs": [], "count": 0}
    monkeypatch.setattr(ai_router, "get_ai_token_budget_status", lambda: {"enabled": True, "daily": {"used_tokens": 10, "limit_tokens": 100, "remaining_tokens": 90}, "monthly": {"used_tokens": 10, "limit_tokens": 1000, "remaining_tokens": 990}})
    budget = client.get("/api/v1/app/ai/budget", headers=headers)
    assert budget.status_code == 200
    assert budget.json()["daily"]["remaining_tokens"] == 90
    monkeypatch.setattr(ai_router, "get_ai_research_run", lambda run_id: None)
    missing = client.get("/api/v1/app/ai/research/runs/not-a-run", headers=headers)
    assert missing.status_code == 404


def test_ai_daily_token_budget_reservation_is_atomic_and_fails_closed(monkeypatch):
    from rovexquant.ai import research_archive

    monkeypatch.setenv("QUANT_AI_DAILY_TOKEN_BUDGET", "100")
    monkeypatch.setenv("QUANT_AI_MONTHLY_TOKEN_BUDGET", "0")

    class Cursor:
        rowcount = 1
        def fetchone(self):
            return (20,)

    class FakeConnection:
        def __init__(self):
            self.queries = []
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def execute(self, query, params=()):
            self.queries.append((query, params))
            return Cursor()

    fake = FakeConnection()
    monkeypatch.setattr(research_archive, "connect", lambda: fake)
    assert research_archive.ai_token_budgets_enabled() is True
    with pytest.raises(EodStoreError, match="daily token budget exhausted"):
        research_archive.reserve_ai_research_run(
            task_type="research",
            provider_id="groq",
            model="research-model",
            credential_id=None,
            request_payload={"messages": [{"role": "user", "content": "test"}]},
            estimated_tokens=90,
        )
    assert any("pg_advisory_xact_lock" in query for query, _ in fake.queries)
    assert not any("INSERT INTO ai_research_runs" in query for query, _ in fake.queries)


def test_ai_research_reservation_records_running_state(monkeypatch):
    from rovexquant.ai import research_archive

    monkeypatch.setenv("QUANT_AI_DAILY_TOKEN_BUDGET", "1000")
    monkeypatch.setenv("QUANT_AI_MONTHLY_TOKEN_BUDGET", "10000")

    class Cursor:
        rowcount = 1
        def fetchone(self):
            return (0,)

    class FakeConnection:
        def __init__(self):
            self.queries = []
        def __enter__(self):
            return self
        def __exit__(self, *args):
            return False
        def execute(self, query, params=()):
            self.queries.append((query, params))
            return Cursor()

    fake = FakeConnection()
    monkeypatch.setattr(research_archive, "connect", lambda: fake)
    run_id = research_archive.reserve_ai_research_run(
        task_type="research",
        provider_id="groq",
        model="research-model",
        credential_id="app",
        request_payload={"messages": [{"role": "user", "content": "test"}]},
        estimated_tokens=100,
        source_refs=[{"provider": "tejhq_eod", "symbol": "RELIANCE"}],
    )
    assert len(run_id) == 36
    insert = next(query for query, _ in fake.queries if "INSERT INTO ai_research_runs" in query)
    assert "'RUNNING'" in insert



def test_research_chat_runs_independent_verifier_and_persists_claim_status(mobile_client, monkeypatch):
    from rovexquant.api.routers import ai as ai_router
    client, headers = mobile_client

    monkeypatch.setattr(ai_router, "resolve_task_route", lambda *args: ("groq", "research-model", []))
    monkeypatch.setattr(ai_router, "reserve_ai_research_run", lambda **kwargs: "00000000-0000-0000-0000-000000000001")

    async def fake_complete_chat(**kwargs):
        return {
            "provider_id": "groq",
            "model": "research-model",
            "credential_id": "app",
            "content": "RELIANCE closed at 1,234.50. [market:EOD:2026-11-20:NSE:RELIANCE]",
            "fallback_used": False,
            "task_type": "research",
            "usage": {"prompt_tokens": 100, "completion_tokens": 30, "total_tokens": 130},
            "cost_usd": None,
            "cost_status": "UNKNOWN_MODEL_PRICING",
        }

    async def fake_verify(**kwargs):
        assert "market:EOD:2026-11-20:NSE:RELIANCE" in kwargs["allowed_source_refs"]
        return {
            "status": "VERIFIED",
            "summary": "1 supported, 0 contradicted, 0 insufficient of 1 claims.",
            "claims": [{
                "claim": "RELIANCE closed at 1,234.50.",
                "source_ref": "market:EOD:2026-11-20:NSE:RELIANCE",
                "evidence_quote": "close 1,234.50",
                "verdict": "supported",
                "reason": "Exact value is present in the supplied snapshot.",
            }],
        }

    persisted = {}
    def fake_finish(run_id, completion, **kwargs):
        persisted.update({"run_id": run_id, "status": kwargs.get("status"), "verification": kwargs.get("verification")})

    monkeypatch.setattr(ai_router, "complete_chat", fake_complete_chat)
    monkeypatch.setattr(ai_router, "verify_research_claims", fake_verify)
    monkeypatch.setattr(ai_router, "finish_ai_research_run", fake_finish)

    response = client.post(
        "/api/v1/app/ai/chat",
        headers=headers,
        json={
            "task_type": "research",
            "messages": [{
                "role": "user",
                "content": "Evidence [market:EOD:2026-11-20:NSE:RELIANCE]: close 1,234.50",
            }],
            "max_tokens": 1000,
        },
    )
    assert response.status_code == 200
    assert response.json()["verification"]["status"] == "VERIFIED"
    assert persisted["status"] == "VERIFIED"
    assert persisted["verification"]["claims"][0]["verdict"] == "supported"
