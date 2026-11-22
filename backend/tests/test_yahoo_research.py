from datetime import date
import pytest
from rovexquant.market_data.yahoo_research import (
    YahooResearchError, download_personal_research_history,
)


class FakeFrame:
    empty = False
    columns = {"Open", "High", "Low", "Close", "Adj Close", "Volume", "Dividends", "Stock Splits"}

    def iterrows(self):
        yield (
            date(2025, 1, 2),
            {"Open": 100.0, "High": 110.0, "Low": 95.0, "Close": 105.0,
             "Adj Close": 103.0, "Volume": 1000, "Dividends": 0.5, "Stock Splits": 0.0},
        )


class FakeYahoo:
    def __init__(self):
        self.calls = []

    def download(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        return FakeFrame()


def test_yahoo_requires_explicit_personal_research_opt_in(monkeypatch):
    monkeypatch.delenv("QUANT_ENABLE_YAHOO_PERSONAL_RESEARCH", raising=False)
    with pytest.raises(YahooResearchError, match="disabled"):
        download_personal_research_history(
            "RELIANCE.NS", start=date(2025, 1, 1), end=date(2025, 2, 1),
            yfinance_module=FakeYahoo(),
        )


def test_yahoo_keeps_adjusted_close_separate(monkeypatch):
    monkeypatch.setenv("QUANT_ENABLE_YAHOO_PERSONAL_RESEARCH", "1")
    fake = FakeYahoo()
    result = download_personal_research_history(
        "RELIANCE.NS", start=date(2025, 1, 1), end=date(2025, 2, 1),
        yfinance_module=fake,
    )
    assert result[0]["close"] == 105.0
    assert result[0]["adjusted_close"] == 103.0
    assert result[0]["dividends"] == 0.5
    assert result[0]["price_adjustment"] == "UNADJUSTED_OHLC_WITH_SEPARATE_ADJ_CLOSE"
    assert fake.calls[0][1]["auto_adjust"] is False
    assert fake.calls[0][1]["actions"] is True


def test_yahoo_rejects_unverified_intraday_interval(monkeypatch):
    monkeypatch.setenv("QUANT_ENABLE_YAHOO_PERSONAL_RESEARCH", "1")
    with pytest.raises(YahooResearchError, match="daily, weekly and monthly"):
        download_personal_research_history(
            "RELIANCE.NS", start=date(2025, 1, 1), end=date(2025, 2, 1),
            interval="1m", yfinance_module=FakeYahoo(),
        )
