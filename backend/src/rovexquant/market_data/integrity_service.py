"""Read-only status queries for historical backfill and official reconciliation."""
from __future__ import annotations

from typing import Any

import psycopg

from rovexquant.market_data.eod_store import EodStoreError, connect

_EXCHANGES = {"NSE", "BSE"}


def latest_data_integrity(exchange: str = "NSE") -> dict[str, Any]:
    market = exchange.strip().upper()
    if market not in _EXCHANGES:
        raise EodStoreError("Exchange must be NSE or BSE", 422)
    try:
        with connect() as connection:
            report = connection.execute(
                """
                SELECT report_id, trading_date, provider, source_url, source_sha256, status,
                       official_rows, reference_rows, matched_rows, mismatch_rows,
                       missing_reference_rows, missing_official_rows, created_at
                FROM market_reconciliation_reports
                WHERE exchange=%s
                ORDER BY trading_date DESC, created_at DESC
                LIMIT 1
                """,
                (market,),
            ).fetchone()
            diffs = connection.execute(
                """
                SELECT kind, symbol, series, field, official_value, stored_value, absolute_difference
                FROM market_reconciliation_diffs
                WHERE report_id=%s
                ORDER BY kind, symbol, series, field
                LIMIT 100
                """,
                (report[0],),
            ).fetchall() if report is not None else []
            backfills = connection.execute(
                """
                SELECT year, status, rows_seen, rows_upserted, source_url, source_sha256,
                       started_at, completed_at, error_message
                FROM market_historical_backfill_runs
                WHERE exchange=%s
                ORDER BY year DESC, started_at DESC
                LIMIT 10
                """,
                (market,),
            ).fetchall()
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError(
            "Historical integrity tables are unavailable; apply database migrations"
        ) from error

    if report is None:
        return {
            "exchange": market, "status": "NOT_RUN", "latest_report": None,
            "backfills": [{
                "year": item[0], "status": item[1], "rows_seen": item[2],
                "rows_upserted": item[3], "source_url": item[4], "source_sha256": item[5],
                "started_at": item[6].isoformat(),
                "completed_at": item[7].isoformat() if item[7] else None,
                "error_message": item[8],
            } for item in backfills],
        }

    return {
        "exchange": market,
        "status": report[5],
        "latest_report": {
            "report_id": str(report[0]), "trading_date": report[1].isoformat(),
            "provider": report[2], "source_url": report[3], "source_sha256": report[4],
            "status": report[5], "official_rows": report[6], "reference_rows": report[7],
            "matched_rows": report[8], "mismatch_rows": report[9],
            "missing_reference_rows": report[10], "missing_official_rows": report[11],
            "created_at": report[12].isoformat(),
            "differences": [{
                "kind": item[0], "symbol": item[1], "series": item[2], "field": item[3],
                "official_value": item[4], "stored_value": item[5],
                "absolute_difference": float(item[6]) if item[6] is not None else None,
            } for item in diffs],
            "differences_truncated": len(diffs) == 100,
        },
        "backfills": [{
            "year": item[0], "status": item[1], "rows_seen": item[2],
            "rows_upserted": item[3], "source_url": item[4], "source_sha256": item[5],
            "started_at": item[6].isoformat(),
            "completed_at": item[7].isoformat() if item[7] else None,
            "error_message": item[8],
        } for item in backfills],
    }
