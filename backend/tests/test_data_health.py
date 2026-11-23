from datetime import datetime, timezone

from rovexquant.market_data.data_health import compose_market_data_health


def test_data_health_is_healthy_only_when_eod_intelligence_and_reconciliation_pass():
    now = datetime(2026, 11, 23, 12, 0, tzinfo=timezone.utc)
    result = compose_market_data_health(
        "NSE",
        eod={"status": "SUCCEEDED", "retrieved_at": now, "rows": 1000, "detail": "Verified"},
        macro=[
            {"provider": "WORLD_BANK", "status": "SUCCEEDED", "updated_at": now, "rows": 20},
            {"provider": "AMFI", "status": "SUCCEEDED", "updated_at": now, "rows": 100},
        ],
        intelligence={"status": "SUCCEEDED", "updated_at": now, "events": 10},
        derived=[],
        historical={"status": "SUCCEEDED", "updated_at": now, "rows": 5000},
        reconciliation={"status": "MATCHED", "updated_at": now, "matched_rows": 1000},
        now=now,
    )
    assert result["overall_status"] == "HEALTHY"
    assert result["market_data_mode"] == "END_OF_DAY_ONLY"


def test_data_health_degrades_on_failed_provider_or_exchange_mismatch():
    now = datetime(2026, 11, 23, 12, 0, tzinfo=timezone.utc)
    result = compose_market_data_health(
        "NSE",
        eod={"status": "SUCCEEDED", "retrieved_at": now, "rows": 1000},
        macro=[{"provider": "AMFI", "status": "FAILED", "updated_at": now, "rows": 0}],
        intelligence={"status": "SUCCEEDED", "updated_at": now, "events": 10},
        derived=[{"kind": "SYMBOL_HISTORY", "status": "FAILED", "updated_at": now, "rows": 0}],
        historical={"status": "NOT_RUN", "updated_at": None, "rows": 0},
        reconciliation={"status": "MISMATCHED", "updated_at": now, "matched_rows": 900},
        now=now,
    )
    assert result["overall_status"] == "DEGRADED"
    assert result["reconciliation"]["status"] == "MISMATCHED"


def test_data_health_marks_stale_sources_without_claiming_realtime():
    now = datetime(2026, 11, 23, 12, 0, tzinfo=timezone.utc)
    result = compose_market_data_health(
        "BSE",
        eod={"status": "SUCCEEDED", "retrieved_at": datetime(2026, 11, 20, tzinfo=timezone.utc), "rows": 10},
        macro=[], intelligence={"status": "NOT_RUN", "updated_at": None, "events": 0},
        derived=[], historical={"status": "NOT_RUN", "updated_at": None, "rows": 0},
        reconciliation={"status": "NOT_RUN", "updated_at": None, "matched_rows": 0},
        now=now,
    )
    assert result["eod"]["freshness"] == "STALE"
    assert result["market_data_mode"] == "END_OF_DAY_ONLY"
    assert result["overall_status"] == "PARTIAL"
