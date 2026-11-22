"""GDELT DOC API adapter for bounded global-news discovery.

GDELT is a discovery source, not an exchange filing, price feed, or verified fact
registry. The adapter preserves article URLs and GDELT's seen timestamp so that
downstream research can verify claims against original publishers.
"""
from __future__ import annotations

from typing import Any

import httpx

GDELT_DOC_ENDPOINT = "https://api.gdeltproject.org/api/v2/doc/doc"
_ALLOWED_TIMESPANS = {"1d", "3d", "7d"}


class GdeltProviderError(RuntimeError):
    """Safe upstream failure for the GDELT discovery adapter."""


async def search_articles(query: str, *, timespan: str = "7d", limit: int = 30) -> list[dict[str, Any]]:
    cleaned_query = " ".join(query.split())
    if not cleaned_query or len(cleaned_query) > 160:
        raise ValueError("query must contain between 1 and 160 characters")
    if timespan not in _ALLOWED_TIMESPANS:
        raise ValueError("timespan must be one of 1d, 3d or 7d")
    if not 1 <= limit <= 50:
        raise ValueError("limit must be between 1 and 50")

    params = {
        "query": cleaned_query,
        "mode": "ArtList",
        "format": "json",
        "maxrecords": limit,
        "sort": "HybridRel",
        "timespan": timespan,
    }
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(10.0, connect=3.0),
            follow_redirects=True,
            headers={"User-Agent": "RovexQuant-Research/1.0"},
        ) as client:
            response = await client.get(GDELT_DOC_ENDPOINT, params=params)
            response.raise_for_status()
            payload = response.json()
    except (httpx.HTTPError, ValueError) as error:
        raise GdeltProviderError("Global news discovery is temporarily unavailable") from error

    if not isinstance(payload, dict) or not isinstance(payload.get("articles", []), list):
        raise GdeltProviderError("Global news discovery returned an invalid response")

    articles: list[dict[str, Any]] = []
    for item in payload.get("articles", [])[:limit]:
        if not isinstance(item, dict):
            continue
        title = item.get("title")
        url = item.get("url")
        if not isinstance(title, str) or not title.strip():
            continue
        if not isinstance(url, str) or not url.startswith(("https://", "http://")):
            continue
        articles.append(
            {
                "title": title.strip()[:500],
                "url": url,
                "domain": str(item.get("domain") or "")[:160],
                "seen_at": str(item.get("seendate") or "")[:32],
                "language": str(item.get("language") or "")[:32],
                "source_country": str(item.get("sourcecountry") or "")[:64],
            }
        )
    return articles
