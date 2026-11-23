"""Durable AI research-run history using the existing market database."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb

from rovexquant.market_data.eod_store import EodStoreError, connect

_MAX_DOCUMENT_BYTES = 128 * 1024


def _bounded(value: Any, label: str) -> dict[str, Any] | list[Any]:
    if not isinstance(value, (dict, list)):
        raise EodStoreError(f"AI research {label} must be an object or array", 422)
    encoded = json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
    if len(encoded.encode("utf-8")) > _MAX_DOCUMENT_BYTES:
        raise EodStoreError(f"AI research {label} exceeds the 128 KB archive limit", 413)
    return value


def persist_ai_research_run(
    *, task_type: str, provider_id: str, model: str, credential_id: str | None,
    request_payload: dict[str, Any] | list[Any], response_payload: dict[str, Any],
    usage: dict[str, Any] | None = None, cost_usd: float | None = None,
    cost_status: str = "NOT_TRACKED", fallback_used: bool = False,
    source_refs: list[dict[str, Any]] | None = None,
) -> str:
    """Persist a successful execution without ever storing provider secrets."""
    if not task_type or len(task_type) > 48 or not provider_id or len(provider_id) > 48:
        raise EodStoreError("Invalid AI research run identity", 422)
    if not model or len(model) > 160:
        raise EodStoreError("Invalid AI research model identifier", 422)
    request_doc = _bounded(request_payload, "request")
    response_doc = _bounded(response_payload, "response")
    usage_doc = usage if isinstance(usage, dict) else {}
    refs_doc = _bounded(source_refs or [], "source references")
    request_hash = hashlib.sha256(
        json.dumps(request_doc, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")
    ).hexdigest()
    run_id = uuid4()
    completed_at = datetime.now(timezone.utc)
    try:
        with connect() as connection:
            connection.execute(
                """
                INSERT INTO ai_research_runs (
                    run_id, task_type, provider_id, model, credential_id, status,
                    request_hash, request_payload, response_payload, token_usage,
                    cost_usd, cost_status, fallback_used, source_refs, created_at, completed_at
                ) VALUES (%s, %s, %s, %s, %s, 'SUCCEEDED', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    run_id, task_type, provider_id, model, credential_id, request_hash,
                    Jsonb(request_doc), Jsonb(response_doc), Jsonb(usage_doc), cost_usd,
                    cost_status[:32], bool(fallback_used), Jsonb(refs_doc), completed_at, completed_at,
                ),
            )
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("AI research history could not be persisted") from error
    return str(run_id)


def list_ai_research_runs(*, limit: int = 30, task_type: str | None = None) -> dict[str, Any]:
    if limit < 1 or limit > 100:
        raise EodStoreError("AI research history limit must be between 1 and 100", 422)
    query = """
        SELECT run_id, task_type, provider_id, model, status, token_usage,
               cost_usd, cost_status, fallback_used, source_refs, created_at, completed_at
        FROM ai_research_runs
    """
    params: tuple[Any, ...]
    if task_type:
        query += " WHERE task_type = %s ORDER BY created_at DESC LIMIT %s"
        params = (task_type, limit)
    else:
        query += " ORDER BY created_at DESC LIMIT %s"
        params = (limit,)
    keys = (
        "run_id", "task_type", "provider_id", "model", "status", "token_usage",
        "cost_usd", "cost_status", "fallback_used", "source_refs", "created_at", "completed_at",
    )
    try:
        with connect() as connection:
            rows = connection.execute(query, params).fetchall()
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("AI research history is unavailable") from error
    return {"runs": [dict(zip(keys, row)) for row in rows], "count": len(rows)}


def get_ai_research_run(run_id: str) -> dict[str, Any] | None:
    keys = (
        "run_id", "task_type", "provider_id", "model", "status", "request_hash",
        "request_payload", "response_payload", "token_usage", "cost_usd",
        "cost_status", "fallback_used", "source_refs", "created_at", "completed_at",
    )
    try:
        with connect() as connection:
            row = connection.execute(
                """
                SELECT run_id, task_type, provider_id, model, status, request_hash,
                       request_payload, response_payload, token_usage, cost_usd,
                       cost_status, fallback_used, source_refs, created_at, completed_at
                FROM ai_research_runs WHERE run_id = %s
                """,
                (run_id,),
            ).fetchone()
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("AI research run could not be loaded") from error
    if row is None:
        return None
    result = dict(zip(keys, row))
    for key in ("run_id", "created_at", "completed_at"):
        if result.get(key) is not None:
            result[key] = str(result[key])
    return result
