from __future__ import annotations

import math
import pytest

from rovexquant.market_data.macro_store import normalize_macro_payload


def test_macro_payload_normalizes_world_bank_and_preserves_source_vintage():
    rows, digest = normalize_macro_payload("WORLD_BANK", {
        "provider": "WORLD_BANK",
        "series": [{
            "key": "inflation", "label": "Inflation", "frequency": "ANNUAL",
            "unit": "PERCENT", "source_url": "https://api.worldbank.org/v2/example",
            "data_delay": "LOW_FREQUENCY_OFFICIAL_RELEASE", "latest_period": "2024",
            "observations": [
                {"period": "2023", "value": 5.4}, {"period": "2024", "value": 4.9},
            ],
        }],
    })
    assert len(rows) == 2
    assert rows[1]["period"] == "2024"
    assert rows[1]["metadata"]["as_of"] == "2024"
    assert len(digest) == 64


def test_macro_payload_is_content_addressed_and_stable_across_retrieval_times():
    payload = {"series": [{
        "key": "gdp", "label": "GDP", "unit": "PERCENT", "frequency": "ANNUAL",
        "source_url": "https://api.worldbank.org/v2/example",
        "observations": [{"period": "2024", "value": 6.5}],
    }]}
    assert normalize_macro_payload("WORLD_BANK", payload)[1] == normalize_macro_payload("WORLD_BANK", payload)[1]


def test_macro_payload_rejects_non_finite_and_duplicate_conflicting_values():
    base = {"series": [{
        "key": "gdp", "unit": "PERCENT", "frequency": "ANNUAL",
        "source_url": "https://api.worldbank.org/v2/example",
        "observations": [{"period": "2024", "value": math.inf}],
    }]}
    with pytest.raises(Exception):
        normalize_macro_payload("WORLD_BANK", base)
    base["series"][0]["observations"] = [
        {"period": "2024", "value": 6.0}, {"period": "2024", "value": 7.0}
    ]
    with pytest.raises(Exception, match="conflicting duplicate"):
        normalize_macro_payload("WORLD_BANK", base)


def test_macro_payload_rejects_untrusted_source_urls():
    with pytest.raises(Exception):
        normalize_macro_payload("WORLD_BANK", {
            "series": [{
                "key": "bad", "source_url": "http://example.com",
                "observations": [{"period": "2024", "value": 1}],
            }],
        })


def test_amfi_nav_is_stored_as_mutual_fund_nav_not_equity_price():
    rows, _ = normalize_macro_payload("AMFI", {
        "source_url": "https://www.amfiindia.com/net-asset-value/nav-download",
        "observations": [{
            "scheme_code": 119551, "scheme_name": "Example Fund", "nav": 123.4,
            "nav_date": "23-Nov-2026", "source_url": "https://www.amfiindia.com/net-asset-value/nav-download",
        }],
    })
    assert rows[0]["series_key"] == "scheme:119551"
    assert rows[0]["unit"] == "NAV"
    assert rows[0]["metadata"]["data_mode"] == "DAILY_MUTUAL_FUND_NAV"



def test_macro_history_unfiltered_query_explicitly_types_nullable_parameters(monkeypatch):
    from rovexquant.market_data import macro_store

    captured = {}

    class Result:
        def fetchall(self):
            return []

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def execute(self, query, params):
            captured["query"] = query
            captured["params"] = params
            return Result()

    monkeypatch.setattr(macro_store, "connect", lambda: Connection())
    result = macro_store.latest_macro_observations(limit=2000)

    assert result["count"] == 0
    assert "%s::text IS NULL" in captured["query"]
    assert captured["params"] == (None, None, None, None, 2000)


def test_macro_history_filtered_query_uses_same_typed_predicates(monkeypatch):
    from rovexquant.market_data import macro_store

    captured = {}

    class Result:
        def fetchall(self):
            return []

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def execute(self, query, params):
            captured["query"] = query
            captured["params"] = params
            return Result()

    monkeypatch.setattr(macro_store, "connect", lambda: Connection())
    result = macro_store.latest_macro_observations(
        provider="WORLD_BANK", series_key="inflation", limit=100
    )

    assert result["count"] == 0
    assert captured["query"].count("%s::text IS NULL") == 2
    assert captured["params"] == ("WORLD_BANK", "WORLD_BANK", "inflation", "inflation", 100)
