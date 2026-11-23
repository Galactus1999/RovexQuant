"""Allowlisted official Indian regulator/government RSS feeds.

RSS entries are primary-source discovery pointers, not adjudicated event facts.
Only links on the configured publisher domains are retained.
"""
from __future__ import annotations

import asyncio
import re
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from typing import Any
from urllib.parse import urlparse

import httpx

OFFICIAL_FEEDS = {
    "RBI": ("https://rbi.org.in/pressreleases_rss.xml", {"rbi.org.in"}),
    "SEBI": ("https://www.sebi.gov.in/sebirss.xml", {"sebi.gov.in"}),
    "PIB": ("https://pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=1", {"pib.gov.in"}),
}
_CACHE_TTL = 600
_cache: tuple[float, dict[str, Any]] | None = None
_lock = asyncio.Lock()


def _text(element: ET.Element, name: str) -> str:
    for child in list(element):
        if child.tag.rsplit("}", 1)[-1].casefold() == name.casefold():
            return (child.text or "").strip()
    return ""


def _seen_at(value: str) -> str:
    try:
        parsed = parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    except (TypeError, ValueError, OverflowError):
        return ""


async def fetch_official_updates(*, query: str = "", limit: int = 30) -> dict[str, Any]:
    global _cache
    if not 1 <= limit <= 100:
        raise ValueError("Official update limit must be between 1 and 100")
    terms = [term.casefold() for term in re.findall(r"[A-Za-z0-9]{3,}", query)
             if term.casefold() not in {"and", "or", "the", "for", "with", "from"}]
    now = time.monotonic()
    if _cache and now - _cache[0] < _CACHE_TTL:
        cached = _cache[1]
        rows = [dict(item) for item in cached["articles"]]
        filtered = [row for row in rows if not terms or any(term in row["title"].casefold() or term in row["summary"].casefold() for term in terms)]
        return {**cached, "articles": filtered[:limit], "cache_hit": True}

    async def fetch_one(provider: str, url: str, domains: set[str]) -> tuple[str, list[dict[str, Any]], str | None]:
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(12.0, connect=4.0),
                follow_redirects=True,
                headers={"User-Agent": "RovexQuant-Research/1.0", "Accept": "application/rss+xml, application/xml, text/xml"},
            ) as client:
                response = await client.get(url)
                response.raise_for_status()
                if len(response.content) > 2 * 1024 * 1024:
                    return provider, [], "feed exceeded 2 MB safety limit"
                root = ET.fromstring(response.content)
            rows = []
            for item in root.iter():
                if item.tag.rsplit("}", 1)[-1].casefold() not in {"item", "entry"}:
                    continue
                title = _text(item, "title")
                link = _text(item, "link")
                if not link:
                    for child in list(item):
                        if child.tag.rsplit("}", 1)[-1].casefold() == "link":
                            link = child.attrib.get("href", "")
                            break
                parsed = urlparse(link)
                if not title or parsed.scheme != "https" or not any(parsed.hostname == domain or parsed.hostname.endswith("." + domain) for domain in domains):
                    continue
                summary = _text(item, "description") or _text(item, "summary")
                published = _text(item, "pubDate") or _text(item, "published") or _text(item, "updated")
                rows.append({
                    "title": title[:500], "url": link, "domain": parsed.hostname,
                    "seen_at": _seen_at(published), "language": "en",
                    "source_country": "India", "summary": summary[:1000],
                    "provider": provider, "evidence_class": "OFFICIAL_PRIMARY_SOURCE_FEED",
                })
                if len(rows) >= limit:
                    break
            return provider, rows, None
        except (httpx.HTTPError, ET.ParseError, ValueError) as error:
            return provider, [], type(error).__name__

    results = await asyncio.gather(*(
        fetch_one(provider, url, domains)
        for provider, (url, domains) in OFFICIAL_FEEDS.items()
    ))
    articles = [article for _, rows, _ in results for article in rows]
    _cache = (time.monotonic(), {
        "provider": "OFFICIAL_RBI_SEBI_PIB_RSS",
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "feed_status": [
            {"provider": provider, "status": "AVAILABLE" if error is None else "UNAVAILABLE",
             "articles": len(rows), "error": error}
            for provider, rows, error in results
        ],
        "articles": articles,
    })
    filtered = [row for row in articles if not terms or any(
        term in row["title"].casefold() or term in row["summary"].casefold() for term in terms
    )]
    return {**_cache[1], "articles": filtered[:limit], "cache_hit": False}
