import json

import pytest
from fastapi.testclient import TestClient

from rovexquant.ai import gateway
from rovexquant.api.main import app


class FakeResponse:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload

    def json(self):
        return self._payload


class FakeAsyncClient:
    responses = []
    catalogue_responses = []
    requests = []

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def post(self, url, headers, json):
        self.requests.append({"url": url, "headers": headers, "json": json})
        return self.responses.pop(0)

    async def get(self, url, headers):
        self.requests.append({"method": "GET", "url": url, "headers": headers})
        return self.catalogue_responses.pop(0)


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("QUANT_AI_GATEWAY_TOKEN", "test-gateway-token")
    monkeypatch.setenv("GROQ_API_KEY", "groq-secret")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "deepseek-secret")
    monkeypatch.setattr(gateway.httpx, "AsyncClient", FakeAsyncClient)
    FakeAsyncClient.responses = []
    FakeAsyncClient.catalogue_responses = []
    FakeAsyncClient.requests = []
    return TestClient(app)


def test_ai_gateway_is_disabled_without_server_token(monkeypatch):
    monkeypatch.delenv("QUANT_AI_GATEWAY_TOKEN", raising=False)
    response = TestClient(app).get("/api/v1/ai/providers", headers={"X-Quant-AI-Gateway-Key": "anything"})
    assert response.status_code == 503


def test_provider_catalogue_never_exposes_api_keys(client):
    response = client.get("/api/v1/ai/providers", headers={"X-Quant-AI-Gateway-Key": "test-gateway-token"})
    assert response.status_code == 200
    body = response.json()
    groq = next(item for item in body["providers"] if item["provider_id"] == "groq")
    assert groq["configured"] is True
    assert groq["credential_ids"] == ["default"]
    assert "groq-secret" not in response.text
    assert "api_key" not in response.text


def test_chat_completion_uses_server_credential_and_returns_normalized_result(client):
    FakeAsyncClient.responses = [FakeResponse(200, {
        "choices": [{"message": {"content": "Evidence-based summary"}}],
        "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
    })]
    response = client.post("/api/v1/ai/chat", headers={"X-Quant-AI-Gateway-Key": "test-gateway-token"}, json={
        "provider_id": "groq", "model": "test-model",
        "messages": [{"role": "user", "content": "Summarise this supplied filing."}],
    })
    assert response.status_code == 200
    assert response.json()["content"] == "Evidence-based summary"
    assert response.json()["provider_id"] == "groq"
    assert FakeAsyncClient.requests[0]["headers"]["Authorization"] == "Bearer groq-secret"
    assert "groq-secret" not in response.text


def test_provider_fallback_on_rate_limit_does_not_rotate_same_provider_keys(client, monkeypatch):
    monkeypatch.setenv("GROQ_API_KEYS_JSON", json.dumps({"primary": "groq-one", "secondary": "groq-two"}))
    monkeypatch.setenv("DEEPSEEK_FALLBACK_MODEL", "deepseek-flash")
    FakeAsyncClient.responses = [
        FakeResponse(429, {"error": "rate limited"}),
        FakeResponse(200, {"choices": [{"message": {"content": "Fallback response"}}]}),
    ]
    response = client.post("/api/v1/ai/chat", headers={"X-Quant-AI-Gateway-Key": "test-gateway-token"}, json={
        "provider_id": "groq", "credential_id": "primary", "model": "fast-model",
        "fallback_providers": ["deepseek"],
        "messages": [{"role": "user", "content": "Analyse the supplied evidence."}],
    })
    assert response.status_code == 200
    assert response.json()["provider_id"] == "deepseek"
    assert response.json()["fallback_used"] is True
    assert [item["headers"]["Authorization"] for item in FakeAsyncClient.requests] == [
        "Bearer groq-one", "Bearer deepseek-secret"
    ]


def test_credential_id_selects_exact_key_without_round_robin(client, monkeypatch):
    monkeypatch.setenv("GROQ_API_KEYS_JSON", json.dumps({"one": "secret-one", "two": "secret-two"}))
    FakeAsyncClient.responses = [FakeResponse(200, {"choices": [{"message": {"content": "ok"}}]})]
    response = client.post("/api/v1/ai/chat", headers={"X-Quant-AI-Gateway-Key": "test-gateway-token"}, json={
        "provider_id": "groq", "credential_id": "two", "model": "test-model",
        "messages": [{"role": "user", "content": "test"}],
    })
    assert response.status_code == 200
    assert FakeAsyncClient.requests[0]["headers"]["Authorization"] == "Bearer secret-two"


def test_chat_requires_gateway_authentication(client):
    response = client.post("/api/v1/ai/chat", json={
        "provider_id": "groq", "model": "test-model",
        "messages": [{"role": "user", "content": "test"}],
    })
    assert response.status_code == 401


def test_encrypted_app_credential_is_preferred_without_exposing_it(monkeypatch):
    from rovexquant.market_data import upstox_credentials

    secret = "owner-managed-groq-key-secret-value-123456"
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    monkeypatch.delenv("GROQ_API_KEYS_JSON", raising=False)
    monkeypatch.setattr(
        upstox_credentials, "get_ai_provider_api_key",
        lambda provider_id: secret if provider_id == "groq" else None,
    )

    credentials = gateway._credentials_for("groq")
    assert credentials[0].credential_id == "app"
    assert credentials[0].api_key == secret
    assert secret not in repr(gateway.configured_providers())


def test_task_routes_are_server_owned_and_validated(monkeypatch):
    monkeypatch.setenv(
        "QUANT_AI_TASK_ROUTES",
        json.dumps({
            "claim_verification": {
                "provider_id": "groq",
                "model": "verifier-model",
                "fallback_providers": ["deepseek", "groq"],
            }
        }),
    )
    provider, model, fallbacks = gateway.resolve_task_route(
        "claim_verification", None, None, None
    )
    assert provider == "groq"
    assert model == "verifier-model"
    assert fallbacks == ["deepseek", "groq"]


def test_task_route_rejects_unsupported_provider_and_task(monkeypatch):
    monkeypatch.setenv(
        "QUANT_AI_TASK_ROUTES",
        json.dumps({
            "claim_verification": {
                "provider_id": "untrusted-provider",
                "model": "verifier-model",
                "fallback_providers": [],
            }
        }),
    )
    with pytest.raises(gateway.GatewayError, match="Invalid provider/model"):
        gateway.configured_task_routes()
    with pytest.raises(gateway.GatewayError, match="Unsupported AI task type"):
        gateway.resolve_task_route("execute_trade", "groq", "model", [])



def test_auto_route_discovers_chat_model_without_task_routes(client, monkeypatch):
    monkeypatch.delenv("QUANT_AI_TASK_ROUTES", raising=False)
    monkeypatch.delenv("GROQ_DEFAULT_MODEL", raising=False)
    FakeAsyncClient.catalogue_responses = [
        FakeResponse(200, {"data": [
            {"id": "text-embedding-latest"},
            {"id": "chat-model-current", "supported_parameters": ["messages", "temperature"]},
        ]})
    ]
    provider, model, fallbacks = __import__(
        "asyncio"
    ).run(gateway.resolve_auto_route(["groq"]))
    assert provider == "groq"
    assert model == "chat-model-current"
    assert fallbacks == []
    assert FakeAsyncClient.requests[0]["url"].endswith("/models")


def test_auto_route_respects_disabled_provider_selection(client, monkeypatch):
    monkeypatch.delenv("QUANT_AI_TASK_ROUTES", raising=False)
    with pytest.raises(gateway.GatewayError, match="All AI providers are disabled"):
        __import__("asyncio").run(gateway.resolve_auto_route([]))


def test_auto_route_skips_unconfigured_and_uses_next_enabled_provider(client, monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    monkeypatch.delenv("GROQ_API_KEYS_JSON", raising=False)
    monkeypatch.delenv("GROQ_DEFAULT_MODEL", raising=False)
    monkeypatch.delenv("DEEPSEEK_DEFAULT_MODEL", raising=False)
    FakeAsyncClient.catalogue_responses = [
        FakeResponse(200, {"data": [{"id": "deepseek-chat-current"}]})
    ]
    provider, model, fallbacks = __import__(
        "asyncio"
    ).run(gateway.resolve_auto_route(["groq", "deepseek"]))
    assert provider == "deepseek"
    assert model == "deepseek-chat-current"
    assert fallbacks == []



def test_chat_automatically_discovers_model_when_no_task_routes_exist(client, monkeypatch):
    monkeypatch.delenv("QUANT_AI_TASK_ROUTES", raising=False)
    monkeypatch.delenv("GROQ_DEFAULT_MODEL", raising=False)
    FakeAsyncClient.catalogue_responses = [
        FakeResponse(200, {"data": [{"id": "embedding-only"}, {"id": "groq-chat-current"}]})
    ]
    FakeAsyncClient.responses = [
        FakeResponse(200, {"choices": [{"message": {"content": "Automatic response"}}]})
    ]
    import asyncio

    result = asyncio.run(gateway.complete_chat(
        provider_id=None,
        model=None,
        enabled_provider_ids=["groq"],
        messages=[{"role": "user", "content": "Test automatic selection."}],
    ))
    assert result["provider_id"] == "groq"
    assert result["model"] == "groq-chat-current"
    assert result["content"] == "Automatic response"
    assert any(request.get("url", "").endswith("/models") for request in FakeAsyncClient.requests)
    assert any(request.get("url", "").endswith("/chat/completions") for request in FakeAsyncClient.requests)
