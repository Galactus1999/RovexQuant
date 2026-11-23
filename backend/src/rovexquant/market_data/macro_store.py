"""Versioned macro observation storage with source and vintage provenance."""
from __future__ import annotations

import hashlib
import json
import math
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from rovexquant.market_data.eod_store import EodStoreError, connect

_PROVIDERS = {"WORLD_BANK", "FRED", "AMFI", "RBIH_DBIE_MIRROR"}


def normalize_macro_payload(provider: str, payload: dict[str, Any]) -> tuple[list[dict[str, Any]], str]:
    source = provider.strip().upper()
    if source not in _PROVIDERS or not isinstance(payload, dict):
        raise EodStoreError("Unsupported macro provider payload", 422)
    normalized: list[dict[str, Any]] = []
    if source == "AMFI":
        for item in payload.get("observations", []):
            if not isinstance(item, dict):
                continue
            series_key = f"scheme:{item.get('scheme_code', '')}"
            period = str(item.get("nav_date") or "")[:32]
            raw_value = item.get("nav")
            metadata = {
                "scheme_name": str(item.get("scheme_name") or "")[:240],
                "isin_growth": item.get("isin_growth"),
                "isin_dividend_reinvestment": item.get("isin_dividend_reinvestment"),
                "data_mode": "DAILY_MUTUAL_FUND_NAV",
            }
            series_items = [{
                "key": series_key, "label": metadata["scheme_name"], "unit": "NAV",
                "frequency": "DAILY",
                "source_url": item.get("source_url") or payload.get("source_url"),
                "observations": [{"period": period, "value": raw_value}],
                "metadata": metadata,
            }]
            for series in series_items:
                _append_series(normalized, source, series)
    else:
        series_items = payload.get("series", [])
        if not isinstance(series_items, list):
            raise EodStoreError("Macro provider series payload is invalid", 422)
        for series in series_items:
            if isinstance(series, dict):
                _append_series(normalized, source, series)

    if not normalized:
        raise EodStoreError("Macro provider returned no valid observations", 422)
    unique: dict[tuple[str, str], dict[str, Any]] = {}
    for row in normalized:
        key = (row["series_key"], row["period"])
        if key in unique and unique[key]["value"] != row["value"]:
            raise EodStoreError("Macro provider returned conflicting duplicate observations", 422)
        unique[key] = row
    rows = sorted(unique.values(), key=lambda item: (item["series_key"], item["period"]))
    canonical = json.dumps(
        [{
            "series_key": row["series_key"], "period": row["period"], "value": row["value"],
            "unit": row["unit"], "frequency": row["frequency"], "label": row["label"],
            "source_url": row["source_url"], "metadata": row["metadata"],
        } for row in rows],
        sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")
    return rows, hashlib.sha256(canonical).hexdigest()


def _append_series(output: list[dict[str, Any]], provider: str, series: dict[str, Any]) -> None:
    key = str(series.get("key") or series.get("series_id") or "").strip()[:160]
    source_url = str(series.get("source_url") or "").strip()
    if not key or not source_url.startswith("https://"):
        return
    unit = str(series.get("unit") or "")[:40]
    frequency = str(series.get("frequency") or "")[:40]
    label = str(series.get("label") or key)[:240]
    metadata = series.get("metadata") if isinstance(series.get("metadata"), dict) else {}
    metadata = {
        **metadata,
        "data_delay": str(series.get("data_delay") or "")[:80],
        "source": str(series.get("source") or provider)[:80],
        "as_of": str(series.get("as_of") or series.get("latest_period") or "")[:32],
        "primary_source": str(series.get("primary_source") or "")[:240],
    }
    observations = series.get("observations")
    if not isinstance(observations, list):
        return
    for observation in observations:
        if not isinstance(observation, dict):
            continue
        period = str(observation.get("period") or "").strip()[:32]
        raw_value = observation.get("value")
        if not period or isinstance(raw_value, bool) or raw_value is None:
            continue
        try:
            value = float(raw_value)
        except (TypeError, ValueError):
            continue
        if not math.isfinite(value):
            continue
        output.append({
            "provider": provider, "series_key": key, "period": period, "value": value,
            "unit": unit, "frequency": frequency, "label": label, "source_url": source_url,
            "metadata": metadata,
        })


def persist_macro_payload(provider: str, payload: dict[str, Any]) -> dict[str, Any]:
    rows, digest = normalize_macro_payload(provider, payload)
    run_id = uuid4()
    started = datetime.now(timezone.utc)
    try:
        with connect() as connection:
            connection.execute(
                "INSERT INTO macro_ingestion_runs (run_id, provider, status, started_at) VALUES (%s, %s, 'RUNNING', %s)",
                (run_id, provider.upper(), started),
            )
        with connect() as connection:
            with connection.transaction():
                connection.cursor().executemany(
                    """
                    INSERT INTO macro_observations
                        (provider, series_key, period, value, unit, frequency, label,
                         source_url, source_sha256, retrieved_at, metadata)
                    VALUES (%(provider)s, %(series_key)s, %(period)s, %(value)s, %(unit)s,
                            %(frequency)s, %(label)s, %(source_url)s, %(digest)s, %(retrieved)s,
                            %(metadata)s)
                    ON CONFLICT (provider, series_key, period, source_sha256) DO NOTHING
                    """,
                    [{
                        **row, "digest": digest, "retrieved": started,
                        "metadata": Jsonb(row["metadata"]),
                    } for row in rows],
                )
                connection.execute(
                    """
                    UPDATE macro_ingestion_runs
                    SET status='SUCCEEDED', rows_seen=%s, rows_upserted=%s,
                        source_sha256=%s, completed_at=%s, error_message=NULL
                    WHERE run_id=%s
                    """,
                    (len(rows), len(rows), digest, datetime.now(timezone.utc), run_id),
                )
    except (EodStoreError, psycopg.Error) as error:
        try:
            with connect() as connection:
                connection.execute(
                    """
                    UPDATE macro_ingestion_runs
                    SET status='FAILED', rows_seen=%s, completed_at=%s, error_message=%s
                    WHERE run_id=%s
                    """,
                    (len(rows), datetime.now(timezone.utc), "Macro persistence failed", run_id),
                )
        except Exception:
            pass
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Macro observation persistence failed") from error
    return {
        "provider": provider.upper(), "status": "SUCCEEDED", "rows_seen": len(rows),
        "rows_upserted": len(rows), "source_sha256": digest,
    }


def record_macro_failure(provider: str, message: str) -> None:
    source = provider.strip().upper()
    if source not in _PROVIDERS:
        raise EodStoreError("Unsupported macro provider", 422)
    try:
        with connect() as connection:
            connection.execute(
                """
                INSERT INTO macro_ingestion_runs
                    (run_id, provider, status, started_at, completed_at, error_message)
                VALUES (%s, %s, 'FAILED', %s, %s, %s)
                """,
                (uuid4(), source, datetime.now(timezone.utc), datetime.now(timezone.utc),
                 " ".join(message.split())[:240]),
            )
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Macro failure status could not be persisted") from error


def latest_macro_observations(
    *, provider: str | None = None, series_key: str | None = None, limit: int = 500
) -> dict[str, Any]:
    if not 1 <= limit <= 2000:
        raise EodStoreError("Macro history limit must be between 1 and 2000", 422)
    source = provider.strip().upper() if provider else None
    if source and source not in _PROVIDERS:
        raise EodStoreError("Unsupported macro provider", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                SELECT DISTINCT ON (provider, series_key, period)
                       provider, series_key, period, value, unit, frequency, label,
                       source_url, source_sha256, retrieved_at, metadata
                FROM macro_observations
                WHERE (%s IS NULL OR provider=%s)
                  AND (%s IS NULL OR series_key=%s)
                ORDER BY provider, series_key, period, retrieved_at DESC
                LIMIT %s
                """,
                (source, source, series_key, series_key, limit),
            ).fetchall()
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Macro history tables are unavailable; apply database migrations") from error
    return {
        "provider": source, "series_key": series_key,
        "observations": [{
            "provider": row[0], "series_key": row[1], "period": row[2],
            "value": float(row[3]), "unit": row[4], "frequency": row[5], "label": row[6],
            "source_url": row[7], "source_sha256": row[8], "retrieved_at": row[9].isoformat(),
            "metadata": row[10],
        } for row in rows],
        "count": len(rows),
    }


def latest_macro_runs(*, limit: int = 20) -> list[dict[str, Any]]:
    if not 1 <= limit <= 100:
        raise EodStoreError("Macro status limit must be between 1 and 100", 422)
    try:
        with connect() as connection:
            rows = connection.execute(
                """
                SELECT provider, status, rows_seen, rows_upserted, source_sha256,
                       started_at, completed_at, error_message
                FROM macro_ingestion_runs
                ORDER BY started_at DESC LIMIT %s
                """,
                (limit,),
            ).fetchall()
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Macro status tables are unavailable; apply database migrations") from error
    return [{
        "provider": row[0], "status": row[1], "rows_seen": row[2], "rows_upserted": row[3],
        "source_sha256": row[4], "started_at": row[5].isoformat(),
        "completed_at": row[6].isoformat() if row[6] else None, "error_message": row[7],
    } for row in rows]
