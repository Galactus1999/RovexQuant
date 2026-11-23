"""Durable global-event archive with conservative current-universe issuer candidates."""
from __future__ import annotations

import hashlib
import re
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from rovexquant.intelligence.analysis import analyze_articles
from rovexquant.market_data.eod_store import EodStoreError, connect
from rovexquant.market_data.gdelt import GdeltProviderError
from rovexquant.market_data.instruments import get_nse_equity_instruments
from rovexquant.market_data.gdelt_service import discover_articles
from rovexquant.market_data.official_feeds import fetch_official_updates

DEFAULT_QUERY = "India OR RBI OR SEBI OR oil OR war OR tariff OR AI OR semiconductor OR defence OR capex OR merger"


class IntelligenceProviderError(RuntimeError):
    """Both global discovery and official announcement feeds are unavailable."""


def map_issuer_candidates(events: list[dict[str, Any]], instruments: list[dict[str, str]]) -> list[dict[str, Any]]:
    """Map only exact full company names or uppercase NSE symbols in the headline."""
    for event in events:
        headline = str(event.get("headline") or "")
        candidates: dict[str, dict[str, Any]] = {}
        for instrument in instruments:
            symbol = str(instrument.get("trading_symbol") or "").strip().upper()
            company = str(instrument.get("name") or "").strip()
            isin = str(instrument.get("isin") or "").strip()
            if not symbol or not isin:
                continue
            if len(company) >= 8 and re.search(
                rf"(?<![A-Za-z0-9]){re.escape(company)}(?![A-Za-z0-9])", headline, re.IGNORECASE
            ):
                candidates[symbol] = {
                    "exchange": "NSE", "symbol": symbol, "isin": isin,
                    "company_name": company, "mapping_method": "EXACT_COMPANY_NAME_IN_HEADLINE",
                    "confidence": 0.94,
                    "evidence": f'Exact company name "{company}" appears in the headline.',
                    "mapping_scope": "CURRENT_NSE_BOD_MASTER",
                    "mapping_source": "UPSTOX_NSE_BOD_MASTER",
                }
            elif len(symbol) >= 4 and re.search(
                rf"(?<![A-Z0-9]){re.escape(symbol)}(?![A-Z0-9])", headline
            ):
                candidates[symbol] = {
                    "exchange": "NSE", "symbol": symbol, "isin": isin,
                    "company_name": company, "mapping_method": "EXACT_UPPERCASE_SYMBOL_IN_HEADLINE",
                    "confidence": 0.82,
                    "evidence": f'Exact uppercase trading symbol "{symbol}" appears in the headline.',
                    "mapping_scope": "CURRENT_NSE_BOD_MASTER",
                    "mapping_source": "UPSTOX_NSE_BOD_MASTER",
                }
            if len(candidates) >= 10:
                break
        event["issuer_mappings"] = sorted(candidates.values(), key=lambda item: item["symbol"])
        if event["issuer_mappings"]:
            event["stock_mapping_status"] = "CANDIDATE_REQUIRES_MANUAL_VERIFICATION"
            event["stock_mapping_note"] = (
                "Candidate mapped by an exact headline match against the current NSE BOD master. "
                "This is not verified company exposure, point-in-time membership, or a recommendation."
            )
        else:
            event["stock_mapping_status"] = "NOT_RESOLVED"
            event["stock_mapping_note"] = (
                "No exact full company-name or uppercase symbol match was found in the current NSE BOD master. "
                "No company exposure is inferred."
            )
    return events


def _canonical_url(value: str) -> str | None:
    try:
        parsed = urlsplit(value.strip())
        if parsed.scheme != "https" or not parsed.hostname:
            return None
        host = parsed.hostname.casefold()
        if parsed.port:
            host = f"{host}:{parsed.port}"
        query = [
            (key, val) for key, val in parse_qsl(parsed.query, keep_blank_values=True)
            if not key.casefold().startswith("utm_")
            and key.casefold() not in {"fbclid", "gclid", "mc_cid", "mc_eid"}
        ]
        return urlunsplit(("https", host, parsed.path or "/", urlencode(query), ""))
    except (TypeError, ValueError):
        return None


def _seen_at(value: str, fallback: datetime) -> datetime:
    if re.fullmatch(r"\d{8}T\d{6}Z", value or ""):
        try:
            return datetime.strptime(value, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    return fallback


def persist_intelligence_events(
    events: list[dict[str, Any]], *, query: str, article_count: int,
    instruments: list[dict[str, str]], gdelt_status: str, official_feed_status: list[dict[str, Any]],
) -> dict[str, Any]:
    """Upsert event clusters, canonical source URLs, current NSE aliases and candidate mappings."""
    run_id = uuid4()
    started = datetime.now(timezone.utc)
    source_count = 0
    mapping_count = 0
    try:
        with connect() as connection:
            connection.execute(
                """
                INSERT INTO intelligence_ingestion_runs
                    (run_id, provider, status, query, started_at, provider_status)
                VALUES (%s, 'GDELT_DOC_API_PLUS_OFFICIAL_RSS', 'RUNNING', %s, %s, %s)
                """,
                (run_id, query[:160], started, Jsonb({
                    "gdelt": gdelt_status, "official_feeds": official_feed_status,
                })),
            )
        with connect() as connection:
            with connection.transaction():
                if instruments:
                    connection.cursor().executemany(
                        """
                        INSERT INTO intelligence_issuer_aliases
                            (exchange, symbol, isin, company_name, instrument_key, source, last_seen_at)
                        VALUES ('NSE', %(trading_symbol)s, %(isin)s, %(name)s, %(instrument_key)s,
                                'UPSTOX_NSE_BOD_MASTER', %(retrieved_at)s)
                        ON CONFLICT (exchange, symbol) DO UPDATE SET
                            isin=EXCLUDED.isin, company_name=EXCLUDED.company_name,
                            instrument_key=EXCLUDED.instrument_key, source=EXCLUDED.source,
                            last_seen_at=EXCLUDED.last_seen_at
                        """,
                        [{**item, "retrieved_at": started} for item in instruments
                         if item.get("trading_symbol") and item.get("isin") and item.get("instrument_key")],
                    )

                for event in events:
                    event_id = str(event.get("event_id") or "")
                    if not re.fullmatch(r"[0-9a-f]{64}", event_id):
                        continue
                    sources = event.get("sources") if isinstance(event.get("sources"), list) else []
                    observed = [_seen_at(str(source.get("seen_at") or ""), started)
                                for source in sources if isinstance(source, dict)]
                    first_seen, last_seen = min(observed, default=started), max(observed, default=started)
                    connection.execute(
                        """
                        INSERT INTO intelligence_events (
                            event_id, headline, event_category, event_tags,
                            classification_method, classification_confidence, confidence_semantics,
                            sector_impacts, stock_mapping_status, stock_mapping_note, impact_status,
                            source_article_count, independent_source_count, first_seen_at, last_seen_at,
                            last_ingested_at
                        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        ON CONFLICT (event_id) DO UPDATE SET
                            headline=EXCLUDED.headline, event_category=EXCLUDED.event_category,
                            event_tags=EXCLUDED.event_tags, classification_method=EXCLUDED.classification_method,
                            classification_confidence=EXCLUDED.classification_confidence,
                            confidence_semantics=EXCLUDED.confidence_semantics,
                            sector_impacts=EXCLUDED.sector_impacts,
                            stock_mapping_status=EXCLUDED.stock_mapping_status,
                            stock_mapping_note=EXCLUDED.stock_mapping_note, impact_status=EXCLUDED.impact_status,
                            first_seen_at=LEAST(intelligence_events.first_seen_at, EXCLUDED.first_seen_at),
                            last_seen_at=GREATEST(intelligence_events.last_seen_at, EXCLUDED.last_seen_at),
                            last_ingested_at=EXCLUDED.last_ingested_at
                        """,
                        (
                            event_id, str(event.get("headline") or "")[:500],
                            str(event.get("event_category") or "GENERAL_MARKET_NEWS")[:64],
                            Jsonb(event.get("event_tags") or []),
                            str(event.get("classification_method") or "")[:64],
                            float(event.get("classification_confidence") or 0),
                            str(event.get("confidence_semantics") or "")[:240],
                            Jsonb(event.get("sector_impacts") or []),
                            str(event.get("stock_mapping_status") or "NOT_RESOLVED")[:64],
                            str(event.get("stock_mapping_note") or "")[:500],
                            str(event.get("impact_status") or "")[:96],
                            int(event.get("source_article_count") or 0),
                            int(event.get("independent_source_count") or 0),
                            first_seen, last_seen, started,
                        ),
                    )
                    source_rows = []
                    for source in sources:
                        if not isinstance(source, dict):
                            continue
                        canonical = _canonical_url(str(source.get("url") or ""))
                        if not canonical:
                            continue
                        source_rows.append({
                            "event_id": event_id,
                            "url_sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
                            "canonical_url": canonical,
                            "title": str(source.get("title") or event.get("headline") or "")[:500],
                            "domain": str(source.get("domain") or "")[:160],
                            "seen_at": _seen_at(str(source.get("seen_at") or ""), started),
                            "language": str(source.get("language") or "")[:32],
                            "source_country": str(source.get("source_country") or "")[:64],
                            "summary": str(source.get("summary") or "")[:1000],
                            "provider": str(source.get("provider") or "GDELT")[:64],
                            "evidence_class": str(source.get("evidence_class") or "GLOBAL_NEWS_DISCOVERY")[:80],
                            "retrieved_at": started,
                        })
                    if source_rows:
                        connection.cursor().executemany(
                            """
                            INSERT INTO intelligence_event_sources (
                                event_id, url_sha256, canonical_url, title, domain, seen_at,
                                language, source_country, summary, provider, evidence_class, retrieved_at
                            ) VALUES (
                                %(event_id)s, %(url_sha256)s, %(canonical_url)s, %(title)s, %(domain)s,
                                %(seen_at)s, %(language)s, %(source_country)s, %(summary)s,
                                %(provider)s, %(evidence_class)s, %(retrieved_at)s
                            )
                            ON CONFLICT (event_id, url_sha256) DO UPDATE SET
                                seen_at=GREATEST(intelligence_event_sources.seen_at, EXCLUDED.seen_at),
                                retrieved_at=EXCLUDED.retrieved_at
                            """,
                            source_rows,
                        )
                        source_count += len(source_rows)
                    issuer_rows = [{
                        "event_id": event_id, **mapping, "mapped_at": started,
                    } for mapping in event.get("issuer_mappings", []) if isinstance(mapping, dict)]
                    if issuer_rows:
                        connection.cursor().executemany(
                            """
                            INSERT INTO intelligence_event_issuers (
                                event_id, exchange, symbol, isin, company_name, mapping_method,
                                confidence, evidence, mapping_scope, mapping_source, mapped_at
                            ) VALUES (
                                %(event_id)s, %(exchange)s, %(symbol)s, %(isin)s, %(company_name)s,
                                %(mapping_method)s, %(confidence)s, %(evidence)s, %(mapping_scope)s,
                                %(mapping_source)s, %(mapped_at)s
                            )
                            ON CONFLICT (event_id, exchange, symbol) DO UPDATE SET
                                isin=EXCLUDED.isin, company_name=EXCLUDED.company_name,
                                mapping_method=EXCLUDED.mapping_method, confidence=EXCLUDED.confidence,
                                evidence=EXCLUDED.evidence, mapping_scope=EXCLUDED.mapping_scope,
                                mapping_source=EXCLUDED.mapping_source, mapped_at=EXCLUDED.mapped_at
                            """,
                            issuer_rows,
                        )
                        mapping_count += len(issuer_rows)
                    counts = connection.execute(
                        "SELECT COUNT(*), COUNT(DISTINCT domain) FROM intelligence_event_sources WHERE event_id=%s",
                        (event_id,),
                    ).fetchone()
                    connection.execute(
                        "UPDATE intelligence_events SET source_article_count=%s, independent_source_count=%s WHERE event_id=%s",
                        (counts[0], counts[1], event_id),
                    )
                connection.execute(
                    """
                    UPDATE intelligence_ingestion_runs
                    SET status='SUCCEEDED', article_count=%s, event_count=%s,
                        source_count=%s, mapping_count=%s, completed_at=%s, error_message=NULL
                    WHERE run_id=%s
                    """,
                    (article_count, len(events), source_count, mapping_count, datetime.now(timezone.utc), run_id),
                )
    except (EodStoreError, psycopg.Error) as error:
        try:
            with connect() as connection:
                connection.execute(
                    """
                    UPDATE intelligence_ingestion_runs
                    SET status='FAILED', article_count=%s, completed_at=%s, error_message=%s
                    WHERE run_id=%s
                    """,
                    (article_count, datetime.now(timezone.utc), "Intelligence archive persistence failed", run_id),
                )
        except Exception:
            pass
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Intelligence archive persistence failed") from error
    return {"run_id": str(run_id), "status": "SUCCEEDED", "events_upserted": len(events),
            "sources_seen": source_count, "issuer_candidates": mapping_count}


def get_intelligence_archive(*, days: int = 30, category: str | None = None, limit: int = 50) -> dict[str, Any]:
    if not 1 <= days <= 365 or not 1 <= limit <= 100:
        raise EodStoreError("Archive days or limit is outside the supported range", 422)
    normalized_category = category.strip().upper() if category else None
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                SELECT event_id, headline, event_category, event_tags,
                       source_article_count, independent_source_count, classification_method,
                       classification_confidence, confidence_semantics, sector_impacts,
                       stock_mapping_status, stock_mapping_note, impact_status,
                       first_seen_at, last_seen_at
                FROM intelligence_events
                WHERE last_seen_at >= NOW() - (%s * INTERVAL '1 day')
                  AND (%s IS NULL OR event_category=%s)
                ORDER BY last_seen_at DESC LIMIT %s
                """,
                (days, normalized_category, normalized_category, limit),
            ).fetchall()
            event_ids = [row[0] for row in rows]
            source_rows = connection.execute(
                """
                SELECT event_id, domain, canonical_url, title, seen_at, language,
                       source_country, summary, provider, evidence_class
                FROM intelligence_event_sources WHERE event_id = ANY(%s) ORDER BY seen_at DESC
                """, (event_ids,),
            ).fetchall() if event_ids else []
            issuer_rows = connection.execute(
                """
                SELECT event_id, exchange, symbol, isin, company_name, mapping_method,
                       confidence, evidence, mapping_scope, mapping_source
                FROM intelligence_event_issuers WHERE event_id = ANY(%s)
                ORDER BY confidence DESC, symbol
                """, (event_ids,),
            ).fetchall() if event_ids else []
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Intelligence archive tables are unavailable; apply database migrations") from error

    sources_by_event: dict[str, list[dict[str, Any]]] = {}
    for item in source_rows:
        sources_by_event.setdefault(item[0], []).append({
            "domain": item[1], "url": item[2], "title": item[3], "seen_at": item[4].isoformat(),
            "language": item[5], "source_country": item[6], "summary": item[7],
            "provider": item[8], "evidence_class": item[9],
        })
    issuers_by_event: dict[str, list[dict[str, Any]]] = {}
    for item in issuer_rows:
        issuers_by_event.setdefault(item[0], []).append({
            "exchange": item[1], "symbol": item[2], "isin": item[3], "company_name": item[4],
            "mapping_method": item[5], "confidence": float(item[6]), "evidence": item[7],
            "mapping_scope": item[8], "mapping_source": item[9],
        })
    events = [{
        "event_id": row[0], "headline": row[1], "event_category": row[2],
        "event_tags": row[3], "source_article_count": row[4], "independent_source_count": row[5],
        "classification_method": row[6], "classification_confidence": float(row[7]),
        "confidence_semantics": row[8], "sector_impacts": row[9],
        "stock_mapping_status": row[10], "stock_mapping_note": row[11],
        "impact_status": row[12], "first_seen_at": row[13].isoformat(),
        "last_seen_at": row[14].isoformat(), "sources": sources_by_event.get(row[0], []),
        "issuer_mappings": issuers_by_event.get(row[0], []),
    } for row in rows]
    return {"days": days, "category": normalized_category, "count": len(events), "events": events}


def latest_intelligence_runs(*, limit: int = 20) -> list[dict[str, Any]]:
    if not 1 <= limit <= 100:
        raise EodStoreError("Intelligence status limit must be between 1 and 100", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                SELECT provider, status, query, article_count, event_count, source_count,
                       mapping_count, started_at, completed_at, error_message
                FROM intelligence_ingestion_runs ORDER BY started_at DESC LIMIT %s
                """, (limit,),
            ).fetchall()
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Intelligence status tables are unavailable; apply migrations") from error
    return [{
        "provider": row[0], "status": row[1], "query": row[2], "article_count": row[3],
        "event_count": row[4], "source_count": row[5], "mapping_count": row[6],
        "started_at": row[7].isoformat(), "completed_at": row[8].isoformat() if row[8] else None,
        "error_message": row[9],
    } for row in rows]


async def discover_and_archive(
    query: str, *, timespan: str = "7d", limit: int = 30,
    gdelt_fetcher: Callable[..., Awaitable[list[dict[str, Any]]]] | None = None,
    official_fetcher: Callable[..., Awaitable[dict[str, Any]]] | None = None,
    instrument_fetcher: Callable[..., Awaitable[list[dict[str, str]]]] | None = None,
) -> dict[str, Any]:
    gdelt_call = gdelt_fetcher or discover_articles
    official_call = official_fetcher or fetch_official_updates
    instrument_call = instrument_fetcher or get_nse_equity_instruments
    gdelt_available = True
    try:
        gdelt_articles = await gdelt_call(query, timespan=timespan, limit=limit)
    except GdeltProviderError:
        gdelt_articles = []
        gdelt_available = False
    official = await official_call(query=query, limit=limit)
    articles = gdelt_articles + official.get("articles", [])
    feed_status = official.get("feed_status", [])
    if not articles and not gdelt_available and not any(
        item.get("status") == "AVAILABLE" for item in feed_status
    ):
        raise IntelligenceProviderError("Global news and official announcement feeds are temporarily unavailable.")

    events = analyze_articles(articles)
    instruments: list[dict[str, str]] = []
    issuer_master_status = "UNAVAILABLE"
    if articles:
        try:
            instruments = await instrument_call()
            issuer_master_status = "AVAILABLE" if instruments else "EMPTY"
        except Exception:
            issuer_master_status = "UNAVAILABLE"
    map_issuer_candidates(events, instruments)
    archive: dict[str, Any] = {"status": "UNAVAILABLE", "error": "Market database is not configured."}
    try:
        archive = persist_intelligence_events(
            events, query=query, article_count=len(articles), instruments=instruments,
            gdelt_status="AVAILABLE" if gdelt_available else "UNAVAILABLE",
            official_feed_status=feed_status,
        )
    except EodStoreError as error:
        archive = {"status": "UNAVAILABLE", "error": str(error)[:240]}

    latest_seen_at = max((str(article.get("seen_at") or "") for article in articles), default=None)
    return {
        "source": "GDELT_DOC_API_PLUS_OFFICIAL_RSS_AND_ARCHIVE",
        "gdelt_status": "AVAILABLE" if gdelt_available else "UNAVAILABLE",
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "latest_source_seen_at": latest_seen_at,
        "timestamp_semantics": "GDELT indexed/seen timestamp; not publisher publication time",
        "data_delay": "NEWS_DISCOVERY_INDEX_DELAY_UNSPECIFIED",
        "query": query, "timespan": timespan, "coverage": "GLOBAL_NEWS_DISCOVERY",
        "article_count": len(articles), "gdelt_article_count": len(gdelt_articles),
        "official_article_count": len(official.get("articles", [])),
        "official_feed_status": feed_status,
        "official_feed_cache_hit": official.get("cache_hit", False),
        "event_count": len(events), "events": events,
        "archive_status": archive["status"], "archive_run": archive.get("run_id"),
        "archive_error": archive.get("error"), "issuer_master_status": issuer_master_status,
        "mapped_event_count": sum(bool(event.get("issuer_mappings")) for event in events),
        "limitations": [
            "GDELT is a global news-discovery index, not an exchange filing or primary-source verification service.",
            "Article seen time is not necessarily the publisher's original publication time.",
            "Headline classification and sector exposure are deterministic hypotheses, not calibrated forecasts.",
            "Issuer matches are candidates from the current NSE BOD master, not point-in-time membership or verified economic exposure.",
            "Only exact full company names and uppercase ticker tokens are mapped; unmatched companies remain unresolved.",
            "The archive deduplicates canonical source URLs and event fingerprints; it does not independently adjudicate article truth.",
            "Verify material claims against the original publisher, company disclosure or regulator before acting.",
            "No order execution or automated investment recommendation is generated.",
        ],
    }


async def run_scheduled_intelligence_refresh() -> dict[str, Any]:
    return await discover_and_archive(DEFAULT_QUERY, timespan="7d", limit=50)
