from __future__ import annotations

import asyncio

from rovexquant.market_data import macro_data


class FakeResponse:
    headers = {"content-type": "application/json"}
    status_code = 200
    def raise_for_status(self):
        return None
    def json(self):
        return [
            {"page": 1, "pages": 1},
            [
                {"date": "2024", "value": 7.2},
                {"date": "2023", "value": None},
                {"date": "2022", "value": 6.5},
            ],
        ]


class FakeAsyncClient:
    def __init__(self, **kwargs):
        self.kwargs = kwargs
    async def __aenter__(self):
        return self
    async def __aexit__(self, *args):
        return False
    async def get(self, url, params):
        self.url, self.params = url, params
        return FakeResponse()


def test_world_bank_adapter_returns_sorted_observations_and_provenance(monkeypatch):
    monkeypatch.setattr(macro_data.httpx, "AsyncClient", FakeAsyncClient)
    result = asyncio.run(macro_data.fetch_world_bank(["gdp_growth"]))
    series = result["series"][0]
    assert series["indicator_code"] == "NY.GDP.MKTP.KD.ZG"
    assert series["country"] == "IND"
    assert series["observations"] == [
        {"period": "2022", "value": 6.5},
        {"period": "2024", "value": 7.2},
    ]
    assert series["latest_period"] == "2024"
    assert series["data_delay"] == "LOW_FREQUENCY_OFFICIAL_RELEASE"
    assert result["provider"] == "WORLD_BANK"


def test_world_bank_adapter_rejects_unapproved_indicator(monkeypatch):
    monkeypatch.setattr(macro_data.httpx, "AsyncClient", FakeAsyncClient)
    try:
        asyncio.run(macro_data.fetch_world_bank(["arbitrary_url_or_indicator"]))
    except ValueError as error:
        assert "Unsupported" in str(error)
    else:
        raise AssertionError("unapproved indicator must be rejected")


def test_fred_adapter_fails_soft_when_server_key_is_not_configured(monkeypatch):
    monkeypatch.delenv("FRED_API_KEY", raising=False)
    result = asyncio.run(macro_data.fetch_fred())
    assert result["configured"] is False
    assert result["series"] == []
    assert "FRED_API_KEY" in result["reason"]


def test_fred_adapter_never_accepts_arbitrary_series(monkeypatch):
    monkeypatch.setenv("FRED_API_KEY", "test-only")
    try:
        asyncio.run(macro_data.fetch_fred(["arbitrary-series"]))
    except ValueError as error:
        assert "Unsupported" in str(error)
    else:
        raise AssertionError("unapproved FRED series must be rejected")


def test_rbi_dbie_mirror_parser_preserves_vintage_and_values():
    observations, as_of, label = macro_data._extract_dbie_observations({
        "as_of": "2026-09",
        "series": [{
            "label": "Foreign exchange reserves",
            "observations": [
                {"period": "2026-W35", "value": 690000.0},
                {"period": "2026-W36", "obs_value": 692500.0},
            ],
        }],
    })
    assert as_of == "2026-09"
    assert label == "Foreign exchange reserves"
    assert observations[-1] == {"period": "2026-W36", "value": 692500.0}


def test_rbi_dbie_mirror_parser_fails_closed_on_unknown_payload():
    try:
        macro_data._extract_dbie_observations({"unexpected": [{"date": "2026", "value": 1}]})
    except macro_data.MacroProviderError:
        pass
    else:
        raise AssertionError("unknown DBIE payload shape must not be guessed")


def test_rbi_dbie_mirror_rejects_unallowlisted_series():
    try:
        import asyncio
        asyncio.run(macro_data.fetch_rbi_dbie_mirror(["arbitrary"]))
    except ValueError as error:
        assert "Unsupported" in str(error)
    else:
        raise AssertionError("unapproved RBI series must be rejected")
