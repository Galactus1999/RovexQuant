from __future__ import annotations

from fastapi.testclient import TestClient

from rovexquant.api.main import app
from rovexquant.auth import issue_app_session
from rovexquant.intelligence.analysis import analyze_articles


def _article(title: str, domain: str, url: str) -> dict[str, str]:
    return {
        "title": title,
        "domain": domain,
        "url": url,
        "seen_at": "20261122T090000Z",
        "language": "English",
        "source_country": "India",
    }


def test_event_analysis_deduplicates_exact_headlines_and_preserves_sources():
    events = analyze_articles([
        _article("Oil supply disruption raises crude oil prices", "one.example", "https://one.example/a"),
        _article("Oil supply disruption raises crude oil prices", "two.example", "https://two.example/b"),
    ])
    assert len(events) == 1
    assert events[0]["source_article_count"] == 2
    assert events[0]["independent_source_count"] == 2
    assert events[0]["event_category"] == "COMMODITY_SUPPLY"
    assert events[0]["stock_mapping_status"] == "NOT_RESOLVED"
    assert {impact["sector"] for impact in events[0]["sector_impacts"]} >= {"AVIATION", "PAINTS"}


def test_war_event_exposes_mixed_and_directional_sector_channels():
    events = analyze_articles([
        _article("Military conflict disrupts shipping routes and crude oil supply", "news.example", "https://news.example/story")
    ])
    assert events[0]["event_category"] == "WAR_CONFLICT"
    directions = {item["direction"] for item in events[0]["sector_impacts"]}
    assert "MIXED" in directions
    assert "POTENTIAL_NEGATIVE" in directions
    assert events[0]["classification_method"] == "DETERMINISTIC_KEYWORD_RULES"
    assert "not a calibrated probability" in events[0]["confidence_semantics"]


def test_unclassified_headline_does_not_receive_invented_sector_impacts():
    events = analyze_articles([
        _article("Local community celebrates annual cultural festival", "local.example", "https://local.example/story")
    ])
    assert events[0]["event_category"] == "GENERAL_MARKET_NEWS"
    assert events[0]["sector_impacts"] == []
    assert events[0]["impact_status"] == "NO_SECTOR_IMPACT_RULE"


def test_mobile_intelligence_endpoint_requires_app_session(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "correct-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "x" * 48)
    token, _ = issue_app_session("quant-test", "correct-password")
    client = TestClient(app)
    assert client.get("/api/v1/app/intelligence/events").status_code == 401

    async def fake_discover(query: str, *, timespan: str, limit: int):
        assert query == "India"
        assert timespan == "3d"
        assert limit == 10
        return [_article("RBI rate cut announced", "publisher.example", "https://publisher.example/rbi")]

    async def fake_official(*, query: str, limit: int):
        return {
            "articles": [],
            "feed_status": [{"provider": "RBI", "status": "AVAILABLE", "articles": 0, "error": None}],
            "cache_hit": True,
        }

    monkeypatch.setattr(
        "rovexquant.api.routers.intelligence.gdelt_service.discover_articles",
        fake_discover,
    )
    monkeypatch.setattr(
        "rovexquant.api.routers.intelligence.fetch_official_updates",
        fake_official,
    )
    response = client.get(
        "/api/v1/app/intelligence/events?query=India&timespan=3d&limit=10",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["source"] == "GDELT_DOC_API_PLUS_OFFICIAL_RSS"
    assert body["gdelt_article_count"] == 1
    assert body["official_article_count"] == 0
    assert body["events"][0]["event_category"] == "MONETARY_POLICY"
    assert body["events"][0]["stock_mapping_status"] == "NOT_RESOLVED"
    assert "stock recommendation" in " ".join(body["limitations"])


def test_mobile_intelligence_endpoint_fails_safely_on_upstream_error(monkeypatch):
    monkeypatch.setenv("QUANT_APP_USERNAME", "quant-test")
    monkeypatch.setenv("QUANT_APP_PASSWORD", "correct-password")
    monkeypatch.setenv("QUANT_APP_SESSION_SIGNING_KEY", "x" * 48)
    token, _ = issue_app_session("quant-test", "correct-password")

    async def fail_discover(*args, **kwargs):
        from rovexquant.market_data.gdelt import GdeltProviderError
        raise GdeltProviderError("Global news discovery is temporarily unavailable")

    async def unavailable_official(*, query: str, limit: int):
        return {
            "articles": [],
            "feed_status": [{"provider": "RBI", "status": "UNAVAILABLE", "articles": 0, "error": "Timeout"}],
            "cache_hit": False,
        }

    monkeypatch.setattr(
        "rovexquant.api.routers.intelligence.gdelt_service.discover_articles",
        fail_discover,
    )
    monkeypatch.setattr(
        "rovexquant.api.routers.intelligence.fetch_official_updates",
        unavailable_official,
    )
    response = TestClient(app).get(
        "/api/v1/app/intelligence/events",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 502
    assert "temporarily unavailable" in response.json()["detail"]
