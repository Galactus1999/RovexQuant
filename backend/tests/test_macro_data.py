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



class FakeAmfiResponse:
    content = (
        "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date\n"
        "135762;INF846K01WO1;-;Axis Children's Fund;Direct Plan;Growth Option;31.2861;23-Nov-2026\n"
        "120586;INF109K01480;-;ICICI Prudential Large Cap Fund;123.4567;21-May-2026\n"
    ).encode("utf-8")

    def raise_for_status(self):
        return None


class FakeAmfiAsyncClient:
    def __init__(self, **kwargs):
        self.kwargs = kwargs

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def get(self, url):
        self.url = url
        return FakeAmfiResponse()


def test_amfi_parser_supports_current_eight_column_and_legacy_six_column_rows(monkeypatch):
    monkeypatch.setattr(macro_data.httpx, "AsyncClient", FakeAmfiAsyncClient)
    result = asyncio.run(macro_data.fetch_amfi_nav(limit=10))
    assert result["result_count"] == 2
    current, legacy = result["observations"]
    assert current["scheme_code"] == 135762
    assert current["nav"] == 31.2861
    assert current["nav_date"] == "23-Nov-2026"
    assert legacy["scheme_code"] == 120586
    assert legacy["nav"] == 123.4567
    assert legacy["nav_date"] == "21-May-2026"


def test_amfi_uses_official_portal_feed_and_follows_redirects(monkeypatch):
    captured = {}

    class CapturingClient(FakeAmfiAsyncClient):
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            captured.update(kwargs)

        async def get(self, url):
            captured["url"] = url
            return FakeAmfiResponse()

    monkeypatch.setattr(macro_data.httpx, "AsyncClient", CapturingClient)
    asyncio.run(macro_data.fetch_amfi_nav(limit=1))
    assert captured["url"] == "https://portal.amfiindia.com/spages/NAVAll.txt"
    assert captured["follow_redirects"] is True



def test_rbi_dbie_mirror_keeps_successful_series_when_another_series_fails(monkeypatch):
    monkeypatch.setenv("RBIH_DBIE_ENABLE", "true")

    async def fake_get_json(url, params):
        if "fr_exg_resv_rn" in url:
            return {
                "series": [{
                    "label": "Foreign exchange reserves",
                    "observations": [{"period": "2026-W36", "value": 692500.0}],
                }],
                "as_of": "2026-09",
            }
        raise macro_data.MacroProviderError("Provider returned HTTP 404")

    monkeypatch.setattr(macro_data, "_get_json", fake_get_json)
    result = asyncio.run(macro_data.fetch_rbi_dbie_mirror(["forex_reserves", "wholesale_price_index"]))
    assert len(result["series"]) == 1
    assert result["series"][0]["key"] == "forex_reserves"
    assert len(result["provider_warnings"]) == 1
    assert "HTTP 404" in result["provider_warnings"][0]


def test_rbi_dbie_mirror_reports_provider_error_when_all_series_fail(monkeypatch):
    monkeypatch.setenv("RBIH_DBIE_ENABLE", "true")

    async def fake_get_json(url, params):
        raise macro_data.MacroProviderError("Provider returned HTTP 503")

    monkeypatch.setattr(macro_data, "_get_json", fake_get_json)
    try:
        asyncio.run(macro_data.fetch_rbi_dbie_mirror(["forex_reserves"]))
    except macro_data.MacroProviderError as error:
        assert "HTTP 503" in str(error)
    else:
        raise AssertionError("all failed RBI mirror series must remain a provider failure")


def test_rbi_dbie_mirror_is_disabled_by_default(monkeypatch):
    monkeypatch.delenv("RBIH_DBIE_ENABLE", raising=False)

    async def unexpected_request(*args, **kwargs):
        raise AssertionError("disabled prototype mirror must not be contacted")

    monkeypatch.setattr(macro_data, "_get_json", unexpected_request)
    result = asyncio.run(macro_data.fetch_rbi_dbie_mirror())
    assert result["configured"] is False
    assert result["series"] == []
    assert "experimental prototype" in result["reason"]
