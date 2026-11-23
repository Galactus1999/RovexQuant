"""Durable AI research-run history using the existing market database."""
from __future__ import annotations

import hashlib
import json
import os
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



def ai_token_budgets_enabled() -> bool:
    return any(os.getenv(name, "").strip() not in ("", "0") for name in (
        "QUANT_AI_DAILY_TOKEN_BUDGET", "QUANT_AI_MONTHLY_TOKEN_BUDGET"
    ))


def estimate_reserved_tokens(request_payload: dict[str, Any] | list[Any], max_output_tokens: int) -> int:
    encoded = json.dumps(request_payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    # UTF-8 bytes are a conservative upper bound for ordinary BPE token counts.
    return len(encoded) + max(1, int(max_output_tokens))


def reserve_ai_research_run(
    *, task_type: str, provider_id: str, model: str, credential_id: str | None,
    request_payload: dict[str, Any] | list[Any], estimated_tokens: int,
    source_refs: list[dict[str, Any]] | None = None,
) -> str:
    """Atomically reserve a run against configured daily/monthly token ceilings."""
    if estimated_tokens < 1 or estimated_tokens > 1_000_000:
        raise EodStoreError("AI request exceeds the per-run token reservation limit", 413)
    request_doc = _bounded(request_payload, "request")
    refs_doc = _bounded(source_refs or [], "source references")
    request_hash = hashlib.sha256(
        json.dumps(request_doc, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")
    ).hexdigest()
    try:
        daily_limit = int(os.getenv("QUANT_AI_DAILY_TOKEN_BUDGET", "0") or "0")
        monthly_limit = int(os.getenv("QUANT_AI_MONTHLY_TOKEN_BUDGET", "0") or "0")
        if daily_limit < 0 or monthly_limit < 0:
            raise ValueError("negative budget")
    except ValueError as error:
        raise EodStoreError("AI token budget configuration must use non-negative integers", 503) from error
    run_id = uuid4()
    now = datetime.now(timezone.utc)
    try:
        with connect() as connection:
            connection.execute("SELECT pg_advisory_xact_lock(18763, 8021)")
            for period, limit in (("day", daily_limit), ("month", monthly_limit)):
                if limit == 0:
                    continue
                used = connection.execute(
                    """
                    SELECT COALESCE(SUM(
                        CASE WHEN status = 'RUNNING'
                        THEN COALESCE(NULLIF(token_usage->>'reserved_tokens', '')::BIGINT, 0)
                        ELSE COALESCE(NULLIF(token_usage->>'total_tokens', '')::BIGINT, 0)
                        END
                    ), 0)
                    FROM ai_research_runs WHERE created_at >= date_trunc(%s, NOW())
                    """,
                    (period,),
                ).fetchone()[0]
                if int(used or 0) + estimated_tokens > limit:
                    period_label = "daily" if period == "day" else "monthly"
                    raise EodStoreError(f"AI {period_label} token budget exhausted", 429)
            connection.execute(
                """
                INSERT INTO ai_research_runs (
                    run_id, task_type, provider_id, model, credential_id, status,
                    request_hash, request_payload, response_payload, token_usage,
                    cost_usd, cost_status, fallback_used, source_refs, created_at
                ) VALUES (%s, %s, %s, %s, %s, 'RUNNING', %s, %s, '{}'::jsonb,
                          %s, NULL, 'PENDING', FALSE, %s, %s)
                """,
                (run_id, task_type, provider_id, model, credential_id, request_hash,
                 Jsonb(request_doc), Jsonb({"reserved_tokens": estimated_tokens}),
                 Jsonb(refs_doc), now),
            )
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("AI research run could not reserve history/budget capacity") from error
    return str(run_id)


def finish_ai_research_run(
    run_id: str, completion: dict[str, Any], *, status: str = "SUCCEEDED",
    verification: dict[str, Any] | None = None,
) -> None:
    allowed_statuses = {"SUCCEEDED", "VERIFIED", "PARTIALLY_VERIFIED", "REJECTED", "VERIFICATION_PENDING"}
    if status not in allowed_statuses:
        raise EodStoreError("Invalid terminal AI research status", 422)
    verification_doc = verification if isinstance(verification, dict) else {}
    response_doc = _bounded({"content": completion.get("content", ""), "verification": verification_doc}, "response")
    raw_usage = completion.get("usage") if isinstance(completion.get("usage"), dict) else {}
    usage = {
        key: int(value) for key, value in raw_usage.items()
        if key in {"prompt_tokens", "completion_tokens", "total_tokens"}
        and isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0
    }
    prompt_tokens = usage.get("prompt_tokens")
    completion_tokens = usage.get("completion_tokens")
    total_tokens = usage.get("total_tokens")
    if not isinstance(total_tokens, int) and isinstance(prompt_tokens, int) and isinstance(completion_tokens, int):
        total_tokens = prompt_tokens + completion_tokens
        usage = {**usage, "total_tokens": total_tokens}
    try:
        with connect() as connection:
            reserved_row = connection.execute(
                "SELECT token_usage->>'reserved_tokens' FROM ai_research_runs WHERE run_id = %s AND status = 'RUNNING'",
                (run_id,),
            ).fetchone()
            reserved_tokens = int(reserved_row[0] or 0) if reserved_row else 0
            if ai_token_budgets_enabled() and not isinstance(total_tokens, int):
                usage = {
                    "reserved_tokens": reserved_tokens,
                    "total_tokens": reserved_tokens,
                    "usage_status": "RESERVATION_FALLBACK",
                }
            result = connection.execute(
                """
                UPDATE ai_research_runs SET status = %s, provider_id = %s, model = %s,
                    credential_id = %s, response_payload = %s, token_usage = %s,
                    cost_usd = %s, cost_status = %s, fallback_used = %s,
                    verification = %s, completed_at = %s
                WHERE run_id = %s AND status = 'RUNNING'
                """,
                (status, completion.get("provider_id"), completion.get("model"), completion.get("credential_id"),
                 Jsonb(response_doc), Jsonb(usage), completion.get("cost_usd"),
                 str(completion.get("cost_status", "NOT_TRACKED"))[:32],
                 bool(completion.get("fallback_used")), Jsonb(verification_doc),
                 datetime.now(timezone.utc), run_id),
            )
            if result.rowcount != 1:
                raise EodStoreError("AI research run reservation was not active", 409)
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("AI research run result could not be persisted") from error


def fail_ai_research_run(run_id: str, error_code: str = "PROVIDER_FAILURE") -> None:
    try:
        with connect() as connection:
            connection.execute(
                """
                UPDATE ai_research_runs SET status = 'FAILED',
                    response_payload = %s,
                    token_usage = jsonb_build_object(
                        'total_tokens', COALESCE(NULLIF(token_usage->>'reserved_tokens', '')::BIGINT, 0),
                        'usage_status', 'RESERVATION_FALLBACK'
                    ),
                    cost_status = 'UNKNOWN', completed_at = %s
                WHERE run_id = %s AND status = 'RUNNING'
                """,
                (Jsonb({"error_code": error_code[:48]}), datetime.now(timezone.utc), run_id),
            )
    except (EodStoreError, psycopg.Error):
        # A failed archive update must not mask the original provider error.
        return


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
        "cost_status", "fallback_used", "source_refs", "verification", "created_at", "completed_at",
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


def get_ai_token_budget_status() -> dict[str, Any]:
    try:
        daily_limit = int(os.getenv("QUANT_AI_DAILY_TOKEN_BUDGET", "0") or "0")
        monthly_limit = int(os.getenv("QUANT_AI_MONTHLY_TOKEN_BUDGET", "0") or "0")
        if daily_limit < 0 or monthly_limit < 0:
            raise ValueError("negative budget")
    except ValueError as error:
        raise EodStoreError("AI token budget configuration must use non-negative integers", 503) from error
    try:
        with connect() as connection:
            values = {}
            for period in ("day", "month"):
                used = connection.execute(
                    """
                    SELECT COALESCE(SUM(
                        CASE WHEN status = 'RUNNING'
                        THEN COALESCE(NULLIF(token_usage->>'reserved_tokens', '')::BIGINT, 0)
                        ELSE COALESCE(NULLIF(token_usage->>'total_tokens', '')::BIGINT, 0)
                        END
                    ), 0)
                    FROM ai_research_runs WHERE created_at >= date_trunc(%s, NOW())
                    """,
                    (period,),
                ).fetchone()[0]
                limit = daily_limit if period == "day" else monthly_limit
                used_value = int(used or 0)
                values[period] = {
                    "used_tokens": used_value,
                    "limit_tokens": limit if limit > 0 else None,
                    "remaining_tokens": max(0, limit - used_value) if limit > 0 else None,
                }
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("AI token budget status is unavailable") from error
    return {"enabled": ai_token_budgets_enabled(), "daily": values["day"], "monthly": values["month"]}
