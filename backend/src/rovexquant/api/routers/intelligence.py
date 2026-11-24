"""Authenticated intelligence discovery and durable archive endpoints."""
from __future__ import annotations

from datetime import datetime, timezone
import re
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from rovexquant.market_data.intelligence_archive import (
    IntelligenceProviderError, discover_and_archive, get_intelligence_archive,
    latest_intelligence_runs,
)
from rovexquant.market_data.eod_store import EodStoreError
from rovexquant.market_data import gdelt_service
from rovexquant.market_data.instruments import get_nse_equity_instruments
from rovexquant.market_data.official_feeds import fetch_official_updates
from rovexquant.security.dependencies import require_app_session
from rovexquant.security.request_controls import rate_limit

router = APIRouter()
intelligence_rate_limit = rate_limit("intelligence-events", limit=8, window_seconds=60)
intelligence_refresh_rate_limit = rate_limit("intelligence-refresh", limit=3, window_seconds=60)


async def _refresh(query: str, timespan: Literal["1d", "3d", "7d"], limit: int):
    try:
        return await discover_and_archive(
            query, timespan=timespan, limit=limit,
            gdelt_fetcher=gdelt_service.discover_articles,
            official_fetcher=fetch_official_updates,
            instrument_fetcher=get_nse_equity_instruments,
        )
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except IntelligenceProviderError as error:
        # Keep the workspace useful when discovery providers time out: serve the
        # matching persisted archive as explicitly stale evidence, never as fresh news.
        try:
            archive = get_intelligence_archive(days=int(timespan[:-1]), limit=limit)
        except EodStoreError:
            raise HTTPException(status_code=502, detail=str(error)) from error
        terms = [
            term.casefold() for term in re.findall(r"[A-Za-z0-9]{3,}", query)
            if term.casefold() not in {"and", "or", "the", "for", "with", "from"}
        ]
        events = archive.get("events", [])
        if terms:
            events = [
                event for event in events
                if any(
                    term in " ".join([
                        str(event.get("headline") or ""),
                        " ".join(str(tag) for tag in event.get("event_tags", [])),
                        " ".join(str(source.get("title") or "") + " " + str(source.get("summary") or "")
                                 for source in event.get("sources", [])),
                    ]).casefold()
                    for term in terms
                )
            ]
        latest_seen = max(
            (str(event.get("last_seen_at") or "") for event in events),
            default=None,
        )
        return {
            "source": "PERSISTED_ARCHIVE_FALLBACK",
            "gdelt_status": "UNAVAILABLE",
            "retrieved_at": datetime.now(timezone.utc).isoformat(),
            "latest_source_seen_at": latest_seen,
            "timestamp_semantics": "Stored source-seen timestamps; not necessarily publisher publication time",
            "data_delay": "STALE_PERSISTED_ARCHIVE_FALLBACK",
            "query": query, "timespan": timespan, "coverage": "PERSISTED_INTELLIGENCE_ARCHIVE",
            "article_count": sum(int(event.get("source_article_count") or 0) for event in events),
            "gdelt_article_count": 0, "official_article_count": 0,
            "official_feed_status": [], "official_feed_cache_hit": False,
            "event_count": len(events), "events": events,
            "archive_status": "STALE_FALLBACK",
            "archive_error": "Live discovery unavailable: " + str(error)[:200],
            "issuer_master_status": "ARCHIVED_CANDIDATES",
            "mapped_event_count": sum(bool(event.get("issuer_mappings")) for event in events),
            "limitations": [
                "Live GDELT and official-feed retrieval failed; these are previously persisted events.",
                "The archive fallback is stale and may not include developments from the selected time window.",
                "Issuer mappings are archived candidates, not verified economic exposure or recommendations.",
                "Verify material claims against the original publisher, company disclosure or regulator.",
            ],
        }


@router.get(
    "/api/v1/app/intelligence/events",
    dependencies=[Depends(require_app_session), Depends(intelligence_rate_limit)],
)
async def app_intelligence_events(
    query: str = Query(default="India OR oil OR war OR technology OR central bank", min_length=1, max_length=160),
    timespan: Literal["1d", "3d", "7d"] = "7d",
    limit: int = Query(default=30, ge=1, le=50),
):
    """On-demand discovery plus durable event/source provenance where DB is configured."""
    return await _refresh(query, timespan, limit)


@router.post(
    "/api/v1/app/intelligence/refresh",
    dependencies=[Depends(require_app_session), Depends(intelligence_refresh_rate_limit)],
)
async def app_intelligence_refresh(
    query: str = Query(default="India OR RBI OR SEBI OR oil OR war OR AI", min_length=1, max_length=160),
    timespan: Literal["1d", "3d", "7d"] = "7d",
    limit: int = Query(default=50, ge=1, le=50),
):
    return await _refresh(query, timespan, limit)


@router.get(
    "/api/v1/app/intelligence/archive",
    dependencies=[Depends(require_app_session), Depends(intelligence_rate_limit)],
)
def app_intelligence_archive(
    days: int = Query(default=30, ge=1, le=365),
    category: str | None = Query(default=None, max_length=64),
    limit: int = Query(default=50, ge=1, le=100),
):
    try:
        return get_intelligence_archive(days=days, category=category, limit=limit)
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error


@router.get(
    "/api/v1/app/intelligence/archive/status",
    dependencies=[Depends(require_app_session), Depends(intelligence_rate_limit)],
)
def app_intelligence_archive_status(limit: int = Query(default=20, ge=1, le=100)):
    try:
        return {"runs": latest_intelligence_runs(limit=limit)}
    except EodStoreError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error
