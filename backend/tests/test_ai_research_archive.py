import pytest

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
    assert ai_token_budgets_enabled() is True
