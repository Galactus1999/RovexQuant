from __future__ import annotations

import pytest

from rovexquant.market_data import gdelt


class FakeResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return {
            "articles": [
                {
                    "title": "RBI announces a policy change",
                    "url": "https://publisher.example/rbi",
                    "domain": "publisher.example",
                    "seendate": "20261122T093000Z",
                    "language": "English",
                    "sourcecountry": "India",
                },
                {"title": "", "url": "https://publisher.example/empty"},
                {"title": "No URL"},
                {"title": "Unsafe URL", "url": "javascript:alert(1)"},
            ]
        }


class FakeAsyncClient:
    captured = None

    def __init__(self, **kwargs):
        self.kwargs = kwargs

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def get(self, url, params):
        type(self).captured = {"url": url, "params": params, "client": self.kwargs}
        return FakeResponse()


@pytest.mark.asyncio
async def test_gdelt_adapter_bounds_query_and_preserves_source_provenance(monkeypatch):
    monkeypatch.setattr(gdelt.httpx, "AsyncClient", FakeAsyncClient)
    articles = await gdelt.search_articles("  India   OR   RBI ", timespan="3d", limit=12)
    assert len(articles) == 1
    assert articles[0]["domain"] == "publisher.example"
    assert articles[0]["seen_at"] == "20261122T093000Z"
    assert articles[0]["url"].startswith("https://")
    assert FakeAsyncClient.captured["params"]["query"] == "India OR RBI"
    assert FakeAsyncClient.captured["params"]["timespan"] == "3d"
    assert FakeAsyncClient.captured["params"]["maxrecords"] == 12
    assert FakeAsyncClient.captured["client"]["timeout"].connect == 3.0


@pytest.mark.asyncio
async def test_gdelt_adapter_rejects_unbounded_or_unsupported_requests():
    with pytest.raises(ValueError):
        await gdelt.search_articles(" ", timespan="7d", limit=10)
    with pytest.raises(ValueError):
        await gdelt.search_articles("India", timespan="30d", limit=10)
    with pytest.raises(ValueError):
        await gdelt.search_articles("India", timespan="7d", limit=100)
