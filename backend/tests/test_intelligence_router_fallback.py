import asyncio

from rovexquant.api.routers import intelligence
from rovexquant.market_data.intelligence_archive import IntelligenceProviderError


def test_intelligence_discovery_uses_stale_persisted_archive_when_live_sources_fail(monkeypatch):
    async def unavailable(*args, **kwargs):
        raise IntelligenceProviderError("Global news providers timed out")

    archived = {
        "events": [
            {
                "event_id": "a" * 64,
                "headline": "Oil prices rise after supply disruption",
                "event_tags": ["ENERGY", "COMMODITIES"],
                "source_article_count": 2,
                "last_seen_at": "2026-11-23T10:00:00+00:00",
                "sources": [{"title": "Oil supply report", "summary": "Crude supply update"}],
                "issuer_mappings": [],
            },
            {
                "event_id": "b" * 64,
                "headline": "Technology company announces product launch",
                "event_tags": ["TECHNOLOGY"],
                "source_article_count": 1,
                "last_seen_at": "2026-11-23T09:00:00+00:00",
                "sources": [{"title": "Product launch", "summary": "New device"}],
                "issuer_mappings": [],
            },
        ]
    }
    monkeypatch.setattr(intelligence, "discover_and_archive", unavailable)
    monkeypatch.setattr(intelligence, "get_intelligence_archive", lambda **kwargs: archived)

    result = asyncio.run(intelligence._refresh("India OR oil", "7d", 30))

    assert result["source"] == "PERSISTED_ARCHIVE_FALLBACK"
    assert result["archive_status"] == "STALE_FALLBACK"
    assert result["gdelt_status"] == "UNAVAILABLE"
    assert result["event_count"] == 1
    assert result["events"][0]["headline"] == "Oil prices rise after supply disruption"
    assert "stale" in result["data_delay"].casefold()
