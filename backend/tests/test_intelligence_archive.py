from rovexquant.market_data.intelligence_archive import _canonical_url, map_issuer_candidates
from rovexquant.intelligence.analysis import analyze_articles


def _article(title: str, domain: str, url: str) -> dict[str, str]:
    return {
        "title": title, "domain": domain, "url": url,
        "seen_at": "20261122T090000Z", "language": "English",
        "source_country": "India",
    }


def test_event_fingerprint_is_full_sha256_and_sources_are_preserved():
    event = analyze_articles([
        _article("RBI announces policy rate cut", "rbi.org.in", "https://rbi.org.in/a"),
        _article("RBI announces policy rate cut", "news.example", "https://news.example/b"),
    ])[0]
    assert len(event["event_id"]) == 64
    assert event["event_key"] == event["event_id"]
    assert len(event["sources"]) == 2
    assert event["independent_source_count"] == 2


def test_issuer_mapping_requires_exact_name_or_uppercase_symbol():
    events = analyze_articles([
        _article("Reliance Industries Limited announces new investment", "news.example", "https://news.example/ril"),
        _article("Reliance may invest in new project", "news.example", "https://news.example/ril2"),
        _article("TCS reports quarterly earnings", "news.example", "https://news.example/tcs"),
    ])
    mapped = map_issuer_candidates(events, [
        {"trading_symbol": "RELIANCE", "name": "Reliance Industries Limited", "isin": "INE002A01018"},
        {"trading_symbol": "TCS", "name": "Tata Consultancy Services Limited", "isin": "INE467B01029"},
    ])
    assert mapped[0]["issuer_mappings"][0]["symbol"] == "RELIANCE"
    assert mapped[0]["stock_mapping_status"] == "CANDIDATE_REQUIRES_MANUAL_VERIFICATION"
    assert mapped[1]["issuer_mappings"] == []
    assert mapped[2]["issuer_mappings"] == []


def test_issuer_mapping_accepts_exact_uppercase_symbol_of_four_or_more_chars():
    events = analyze_articles([
        _article("HDFCBANK announces quarterly results", "news.example", "https://news.example/bank"),
    ])
    mapped = map_issuer_candidates(events, [
        {"trading_symbol": "HDFCBANK", "name": "HDFC Bank Limited", "isin": "INE040A01034"},
    ])
    assert mapped[0]["issuer_mappings"][0]["mapping_method"] == "EXACT_UPPERCASE_SYMBOL_IN_HEADLINE"


def test_canonical_source_url_removes_tracking_and_rejects_insecure_links():
    assert _canonical_url("https://News.Example/story?utm_source=x&id=2#fragment") == "https://news.example/story?id=2"
    assert _canonical_url("http://news.example/story") is None
