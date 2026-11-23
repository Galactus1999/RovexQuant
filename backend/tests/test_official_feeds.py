from __future__ import annotations

import asyncio

from rovexquant.market_data import official_feeds


class FakeResponse:
    content = b"""<?xml version="1.0"?>
    <rss><channel><item>
      <title>RBI monetary policy announcement</title>
      <link>https://www.rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=123</link>
      <description>Reserve Bank policy update</description>
      <pubDate>Mon, 23 Nov 2026 10:00:00 GMT</pubDate>
    </item></channel></rss>"""
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
        return FakeResponse()


def test_official_feed_parser_keeps_only_allowlisted_primary_source_links(monkeypatch):
    monkeypatch.setattr(
        official_feeds,
        "OFFICIAL_FEEDS",
        {"RBI": ("https://rbi.example/feed.xml", {"rbi.org.in"})},
    )
    monkeypatch.setattr(official_feeds.httpx, "AsyncClient", FakeAsyncClient)
    monkeypatch.setattr(official_feeds, "_cache", None)
    result = asyncio.run(official_feeds.fetch_official_updates(query="monetary policy"))
    assert result["provider"] == "OFFICIAL_RBI_SEBI_PIB_RSS"
    assert result["feed_status"][0]["status"] == "AVAILABLE"
    assert len(result["articles"]) == 1
    article = result["articles"][0]
    assert article["evidence_class"] == "OFFICIAL_PRIMARY_SOURCE_FEED"
    assert article["domain"] == "www.rbi.org.in"
    assert article["seen_at"] == "20261123T100000Z"


def test_official_feed_filter_does_not_return_unmatched_items(monkeypatch):
    monkeypatch.setattr(
        official_feeds,
        "OFFICIAL_FEEDS",
        {"RBI": ("https://rbi.example/feed.xml", {"rbi.org.in"})},
    )
    monkeypatch.setattr(official_feeds.httpx, "AsyncClient", FakeAsyncClient)
    monkeypatch.setattr(official_feeds, "_cache", None)
    result = asyncio.run(official_feeds.fetch_official_updates(query="semiconductors"))
    assert result["articles"] == []
