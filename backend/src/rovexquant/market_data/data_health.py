"""Aggregated freshness and ingestion health for persisted market/research data."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import psycopg

from rovexquant.market_data.eod_store import EodStoreError, connect


def _age_hours(value: datetime | None, now: datetime) -> float | None:
    if value is None:
        return None
    timestamp = value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    return round(max(0.0, (now - timestamp).total_seconds() / 3600.0), 2)


def _item(name: str, status: str, updated_at: datetime | None, records: int | None,
          detail: str | None, now: datetime, stale_after_hours: float | None = None) -> dict[str, Any]:
    age = _age_hours(updated_at, now)
    freshness = "UNKNOWN" if age is None else (
        "STALE" if stale_after_hours is not None and age > stale_after_hours else "FRESH"
    )
    return {
        "name": name, "status": status, "updated_at": updated_at.isoformat() if updated_at else None,
        "age_hours": age, "freshness": freshness, "records": records, "detail": detail,
    }


def compose_market_data_health(
    exchange: str, *, eod: dict[str, Any], macro: list[dict[str, Any]],
    intelligence: dict[str, Any], derived: list[dict[str, Any]],
    historical: dict[str, Any], reconciliation: dict[str, Any],
    now: datetime | None = None,
) -> dict[str, Any]:
    current = now or datetime.now(timezone.utc)
    eod_item = _item(f"{exchange} EOD", eod["status"], eod.get("retrieved_at"),
                     eod.get("rows"), eod.get("detail"), current, 72)
    macro_items = [
        _item(item["provider"], item["status"], item.get("updated_at"), item.get("rows"),
              item.get("detail"), current,
              72 if item["provider"] == "AMFI" else 30 * 24
              if item["provider"] in {"FRED", "RBIH_DBIE_MIRROR"} else 365 * 24)
        for item in macro
    ]
    intelligence_item = _item("GDELT + official RSS", intelligence["status"],
                               intelligence.get("updated_at"), intelligence.get("events"),
                               intelligence.get("detail"), current, 4)
    derived_items = [
        _item(item["kind"], item["status"], item.get("updated_at"), item.get("rows"),
              item.get("detail"), current) for item in derived
    ]
    if historical.get("status") != "NOT_RUN":
        derived_items.append(_item("HISTORICAL_EOD", historical["status"],
                                   historical.get("updated_at"), historical.get("rows"),
                                   historical.get("detail"), current))
    reconciliation_item = _item("Official exchange reconciliation", reconciliation["status"],
                                reconciliation.get("updated_at"), reconciliation.get("matched_rows"),
                                reconciliation.get("detail"), current, 72)
    statuses = [eod_item["status"], intelligence_item["status"],
                *(item["status"] for item in macro_items),
                *(item["status"] for item in derived_items), reconciliation_item["status"]]
    if any(status in {"FAILED", "MISMATCHED"} for status in statuses):
        overall = "DEGRADED"
    elif (eod_item["records"] and intelligence_item["status"] == "SUCCEEDED"
          and reconciliation_item["status"] == "MATCHED"):
        required_macro = {item["name"]: item["status"] for item in macro_items}
        required_derived = {item["name"]: item["status"] for item in derived_items}
        required_history = all(
            required_derived.get(kind) == "SUCCEEDED"
            for kind in ("ADJUSTED_PRICES", "CORPORATE_ACTIONS", "SYMBOL_HISTORY")
        )
        overall = (
            "HEALTHY" if required_macro.get("WORLD_BANK") == "SUCCEEDED"
            and required_macro.get("AMFI") == "SUCCEEDED" and required_history else "PARTIAL"
        )
    else:
        overall = "PARTIAL" if eod_item["records"] else "NO_DATA"
    return {
        "exchange": exchange, "overall_status": overall, "generated_at": current.isoformat(),
        "market_data_mode": "END_OF_DAY_ONLY", "eod": eod_item,
        "macro_providers": macro_items, "intelligence": intelligence_item,
        "derived_backfills": derived_items, "reconciliation": reconciliation_item,
        "notes": [
            "No intraday feed is represented as complete EOD history.",
            "Provider success means ingestion completed; it does not mean every record is exchange-verified.",
            "Reconciliation status is independent of ingestion status and must be MATCHED before calling EOD data verified.",
            "FRED and RBIH DBIE are optional; DBIE is a delayed static mirror, not a live RBI API.",
            "Point-in-time research depends on completeness of the historical symbol/ISIN archive.",
        ],
    }


def get_market_data_health(exchange: str = "NSE") -> dict[str, Any]:
    market = exchange.strip().upper()
    if market not in {"NSE", "BSE"}:
        raise EodStoreError("Exchange must be NSE or BSE", 422)
    try:
        with connect() as connection:
            eod_row = connection.execute(
                """
                SELECT MAX(trading_date), MAX(source_retrieved_at), COUNT(*),
                       COUNT(*) FILTER (WHERE quality_status='VERIFIED_SOURCE'),
                       COUNT(*) FILTER (WHERE quality_status='PROVISIONAL')
                FROM market_eod_bars WHERE exchange=%s
                """, (market,),
            ).fetchone()
            market_run = connection.execute(
                """
                SELECT provider, status, rows_upserted, completed_at, error_message
                FROM market_ingestion_runs WHERE exchange=%s
                ORDER BY started_at DESC LIMIT 1
                """, (market,),
            ).fetchone()
            macro_rows = connection.execute(
                """
                SELECT DISTINCT ON (provider) provider, status, rows_upserted,
                       completed_at, error_message
                FROM macro_ingestion_runs ORDER BY provider, started_at DESC
                """,
            ).fetchall()
            intelligence_row = connection.execute(
                """
                SELECT status, event_count, source_count, mapping_count,
                       completed_at, error_message
                FROM intelligence_ingestion_runs ORDER BY started_at DESC LIMIT 1
                """,
            ).fetchone()
            derived_rows = connection.execute(
                """
                SELECT DISTINCT ON (dataset_kind) dataset_kind, status, rows_upserted,
                       completed_at, error_message
                FROM market_derived_backfill_runs WHERE exchange=%s
                ORDER BY dataset_kind, started_at DESC
                """, (market,),
            ).fetchall()
            historical_row = connection.execute(
                """
                SELECT status, rows_upserted, completed_at, error_message
                FROM market_historical_backfill_runs WHERE exchange=%s
                ORDER BY started_at DESC LIMIT 1
                """, (market,),
            ).fetchone()
            reconciliation_row = connection.execute(
                """
                SELECT status, trading_date, matched_rows, mismatch_rows,
                       missing_reference_rows, missing_official_rows, created_at
                FROM market_reconciliation_reports WHERE exchange=%s
                ORDER BY trading_date DESC, created_at DESC LIMIT 1
                """, (market,),
            ).fetchone()
    except (EodStoreError, psycopg.Error) as error:
        if isinstance(error, EodStoreError):
            raise
        raise EodStoreError("Market data health tables are unavailable; apply database migrations") from error

    eod_date, eod_retrieved, eod_rows, verified_rows, provisional_rows = eod_row
    run_status = market_run[1] if market_run else ("AVAILABLE" if eod_rows else "NO_DATA")
    run_detail = market_run[4] if market_run else None
    eod = {
        "status": run_status, "retrieved_at": eod_retrieved, "rows": int(eod_rows),
        "detail": f"Latest trading date {eod_date}; verified {verified_rows}; provisional {provisional_rows}."
        + (f" Latest ingestion error: {run_detail}" if run_detail else ""),
    }
    macro = [{
        "provider": row[0], "status": row[1], "rows": int(row[2]),
        "updated_at": row[3], "detail": row[4],
    } for row in macro_rows]
    intelligence = {
        "status": intelligence_row[0] if intelligence_row else "NOT_RUN",
        "events": int(intelligence_row[1]) if intelligence_row else 0,
        "updated_at": intelligence_row[4] if intelligence_row else None,
        "detail": (f"{intelligence_row[2]} canonical sources; {intelligence_row[3]} issuer candidates."
                   if intelligence_row else "No intelligence ingestion run stored."),
    }
    derived = [{
        "kind": row[0], "status": row[1], "rows": int(row[2]),
        "updated_at": row[3], "detail": row[4],
    } for row in derived_rows]
    historical = {
        "status": historical_row[0] if historical_row else "NOT_RUN",
        "rows": int(historical_row[1]) if historical_row else 0,
        "updated_at": historical_row[2] if historical_row else None,
        "detail": historical_row[3] if historical_row else "No historical EOD backfill run stored.",
    }
    reconciliation = {
        "status": reconciliation_row[0] if reconciliation_row else "NOT_RUN",
        "updated_at": reconciliation_row[6] if reconciliation_row else None,
        "matched_rows": int(reconciliation_row[2]) if reconciliation_row else 0,
        "detail": (
            f"Date {reconciliation_row[1]}; mismatches {reconciliation_row[3]}; "
            f"missing stored {reconciliation_row[4]}; extra stored {reconciliation_row[5]}."
            if reconciliation_row else "No official exchange reconciliation report stored."
        ),
    }
    return compose_market_data_health(
        market, eod=eod, macro=macro, intelligence=intelligence, derived=derived,
        historical=historical, reconciliation=reconciliation,
    )
