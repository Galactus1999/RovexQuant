import json
import pytest

from rovexquant.ai import research_archive

from rovexquant.ai.research_archive import (
    _bounded,
    ai_token_budgets_enabled,
    estimate_reserved_tokens,
)
from rovexquant.market_data.eod_store import EodStoreError


def test_research_archive_bounds_json_documents():
    assert _bounded({"source": "NSE"}, "request") == {"source": "NSE"}
    assert _bounded([{"source_ref": "market:EOD:2026-11-20"}], "references")
    with pytest.raises(EodStoreError, match="object or array"):
        _bounded("unstructured text", "request")
    with pytest.raises(EodStoreError, match="128 KB"):
        _bounded({"payload": "x" * (128 * 1024)}, "request")


def test_reserved_token_estimate_is_deterministic_and_includes_output_budget():
    payload = {"symbol": "RELIANCE", "evidence": "validated EOD"}
    first = estimate_reserved_tokens(payload, 1200)
    second = estimate_reserved_tokens(payload, 1200)
    assert first == second
    assert first > 1200
    assert estimate_reserved_tokens(payload, 1600) == first + 400


def test_ai_token_budget_feature_is_disabled_unless_a_limit_is_configured(monkeypatch):
    monkeypatch.delenv("QUANT_AI_DAILY_TOKEN_BUDGET", raising=False)
    monkeypatch.delenv("QUANT_AI_MONTHLY_TOKEN_BUDGET", raising=False)
    assert ai_token_budgets_enabled() is False

    monkeypatch.setenv("QUANT_AI_DAILY_TOKEN_BUDGET", "50000")
    assert ai_token_budgets_enabled() is True

    monkeypatch.setenv("QUANT_AI_DAILY_TOKEN_BUDGET", "0")
    monkeypatch.setenv("QUANT_AI_MONTHLY_TOKEN_BUDGET", "0")
    assert ai_token_budgets_enabled() is False



def test_ai_daily_token_budget_reservation_is_atomic_and_fails_closed(monkeypatch):
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
    with pytest.raises(EodStoreError, match="daily token budget exhausted"):
        research_archive.reserve_ai_research_run(
            task_type="research", provider_id="groq", model="research-model",
            credential_id=None, request_payload={"messages": [{"role": "user", "content": "test"}]},
            estimated_tokens=90,
        )
    assert any("pg_advisory_xact_lock" in query for query, _ in fake.queries)
    assert not any("INSERT INTO ai_research_runs" in query for query, _ in fake.queries)


def test_ai_research_reservation_records_running_state(monkeypatch):
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
        task_type="research", provider_id="groq", model="research-model",
        credential_id="app", request_payload={"messages": [{"role": "user", "content": "test"}]},
        estimated_tokens=100, source_refs=[{"provider": "tejhq_eod", "symbol": "RELIANCE"}],
    )
    assert len(run_id) == 36
    insert = next(query for query, _ in fake.queries if "INSERT INTO ai_research_runs" in query)
    assert "'RUNNING'" in insert


def test_research_archive_redacts_secrets_from_request_and_response():
    api_secret = "sk-test-" + ("x" * 32)
    bearer = "Bearer " + ("y" * 32)
    request = {
        "messages": [{"role": "user", "content": f"api_key={api_secret} {bearer}"}],
        "api_key": "private-secret-value",
        "token_usage": {"total_tokens": 130},
    }
    sanitized = research_archive._bounded(request, "request")
    encoded = json.dumps(sanitized)
    assert "private-secret-value" not in encoded
    assert api_secret not in encoded
    assert bearer not in encoded
    assert sanitized["token_usage"]["total_tokens"] == 130
    provider_secret = "gsk_" + ("z" * 32)
    model_output = research_archive._bounded({"content": provider_secret}, "response")
    assert provider_secret not in json.dumps(model_output)
