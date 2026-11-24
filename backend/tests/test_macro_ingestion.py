from __future__ import annotations

import asyncio

from rovexquant.market_data import macro_ingestion


def test_refresh_reports_fred_key_configuration_separately_from_persistence(monkeypatch):
    async def empty_payload():
        return {"configured": True, "series": [{"key": "test", "observations": []}]}

    async def fred_payload():
        return {"configured": True, "series": [{"key": "us_10y_treasury", "observations": []}]}

    monkeypatch.setattr(macro_ingestion, "fetch_world_bank", empty_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_fred", fred_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_amfi_nav", empty_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_rbi_dbie_mirror", empty_payload)
    monkeypatch.setattr(
        macro_ingestion, "persist_macro_payload",
        lambda name, payload: {"provider": name, "status": "SUCCEEDED", "rows_seen": 1},
    )

    result = asyncio.run(macro_ingestion.refresh_macro_sources())
    fred = next(item for item in result["providers"] if item["provider"] == "FRED")
    assert fred["configured"] is True
    assert fred["status"] == "SUCCEEDED"


def test_refresh_marks_fred_configured_even_when_upstream_fetch_fails(monkeypatch):
    monkeypatch.setenv("FRED_API_KEY", "test-only-secret")

    async def failed_fred():
        raise RuntimeError("upstream timeout")

    async def empty_payload():
        return {"configured": True, "series": [{"key": "test", "observations": []}]}

    monkeypatch.setattr(macro_ingestion, "fetch_world_bank", empty_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_fred", failed_fred)
    monkeypatch.setattr(macro_ingestion, "fetch_amfi_nav", empty_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_rbi_dbie_mirror", empty_payload)
    monkeypatch.setattr(macro_ingestion, "persist_macro_payload",
                        lambda name, payload: {"provider": name, "status": "SUCCEEDED", "rows_seen": 1})
    monkeypatch.setattr(macro_ingestion, "record_macro_failure", lambda *args: None)

    result = asyncio.run(macro_ingestion.refresh_macro_sources())
    fred = next(item for item in result["providers"] if item["provider"] == "FRED")
    assert fred["configured"] is True
    assert fred["status"] == "FAILED"
    assert "test-only-secret" not in str(result)



def test_refresh_preserves_partial_provider_warnings(monkeypatch):
    async def payload_with_warning():
        return {
            "configured": True,
            "series": [{
                "key": "forex_reserves",
                "unit": "USD_MILLION",
                "frequency": "WEEKLY",
                "source_url": "https://dbie.rbihub.in/data/sdmx-fr_exg_resv_rn.json",
                "observations": [{"period": "2026-W36", "value": 692500.0}],
            }],
            "provider_warnings": ["whole_price_index_rn: Provider returned HTTP 404"],
        }

    async def normal_payload():
        return {
            "configured": True,
            "series": [{
                "key": "test",
                "unit": "INDEX",
                "frequency": "ANNUAL",
                "source_url": "https://example.org/series",
                "observations": [{"period": "2026", "value": 1.0}],
            }],
        }

    monkeypatch.setattr(macro_ingestion, "fetch_world_bank", normal_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_fred", normal_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_amfi_nav", normal_payload)
    monkeypatch.setattr(macro_ingestion, "fetch_rbi_dbie_mirror", payload_with_warning)
    monkeypatch.setattr(
        macro_ingestion, "persist_macro_payload",
        lambda name, payload: {"provider": name, "status": "SUCCEEDED", "rows_seen": 1},
    )

    result = asyncio.run(macro_ingestion.refresh_macro_sources())
    rbi = next(item for item in result["providers"] if item["provider"] == "RBIH_DBIE_MIRROR")
    assert rbi["status"] == "SUCCEEDED"
    assert rbi["warnings"] == ["whole_price_index_rn: Provider returned HTTP 404"]
