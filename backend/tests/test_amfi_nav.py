from __future__ import annotations

import asyncio

from rovexquant.market_data import macro_data


class FakeResponse:
    def __init__(self, content: bytes):
        self.content = content
    def raise_for_status(self):
        return None


class FakeAsyncClient:
    def __init__(self, **kwargs):
        pass
    async def __aenter__(self):
        return self
    async def __aexit__(self, *args):
        return False
    async def get(self, url):
        assert url == macro_data.AMFI_NAV_ENDPOINT
        return FakeResponse(
            b"Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date\n"
            b"119551;INF123A01011;;Example Equity Fund - Direct Plan - Growth;123.4567;21-Nov-2026\n"
            b"bad;ignore;ignore;invalid;notnav;date\n"
        )


def test_amfi_nav_parser_keeps_fund_nav_separate_from_equity_quotes(monkeypatch):
    monkeypatch.setattr(macro_data.httpx, "AsyncClient", FakeAsyncClient)
    result = asyncio.run(macro_data.fetch_amfi_nav("example equity", 10))
    assert result["provider"] == "AMFI"
    assert result["data_mode"] == "DAILY_MUTUAL_FUND_NAV"
    assert result["not_equity_quotes"] is True
    assert result["result_count"] == 1
    row = result["observations"][0]
    assert row["scheme_code"] == 119551
    assert row["nav"] == 123.4567
    assert row["nav_date"] == "21-Nov-2026"


def test_amfi_nav_rejects_oversized_search():
    try:
        asyncio.run(macro_data.fetch_amfi_nav("x" * 101))
    except ValueError:
        pass
    else:
        raise AssertionError("oversized search must be rejected")
