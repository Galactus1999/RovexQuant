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
