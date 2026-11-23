"""Shared, idempotent TejHQ EOD collection entry point for API and scheduled jobs."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from uuid import uuid4

import psycopg

from rovexquant.market_data.eod_store import (
    EodStoreError,
    connect,
    fail_run,
    normalize_snapshot_rows,
    persist_snapshot,
)
from rovexquant.market_data.tejhq_service import fetch_latest_snapshot


def claim_ingestion_run(exchange: str, *, force: bool = False) -> tuple[str, bool, str]:
    """Atomically reserve one exchange collection across API and scheduled workers.

    A six-hour success cooldown prevents login storms from repeatedly collecting
    the same EOD batch. Manual refresh and scheduled jobs may bypass that cooldown,
    but never an active run. Abandoned RUNNING rows are failed after 30 minutes.
    """
    market = exchange.strip().upper()
    if market not in {"NSE", "BSE"}:
        raise EodStoreError("Exchange must be NSE or BSE", 422)

    try:
        with connect() as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"rovexquant:eod:{market}",),
            )
            connection.execute(
                """
                UPDATE market_ingestion_runs
                SET status='FAILED', completed_at=NOW(),
                    error_code='STALE_RUN',
                    error_message='Collection lease expired before completion'
                WHERE exchange=%s AND status='RUNNING'
                  AND started_at < NOW() - INTERVAL '30 minutes'
                """,
                (market,),
            )
            running = connection.execute(
                """
                SELECT run_id FROM market_ingestion_runs
                WHERE exchange=%s AND status='RUNNING'
                ORDER BY started_at DESC LIMIT 1
                """,
                (market,),
            ).fetchone()
            if running:
                return str(running[0]), False, "RUNNING"

            if not force:
                recent = connection.execute(
                    """
                    SELECT run_id FROM market_ingestion_runs
                    WHERE exchange=%s AND status='SUCCEEDED'
                      AND completed_at >= NOW() - INTERVAL '6 hours'
                    ORDER BY completed_at DESC LIMIT 1
                    """,
                    (market,),
                ).fetchone()
                if recent:
                    return str(recent[0]), False, "SUCCEEDED"

            run_id = uuid4()
            connection.execute(
                """
                INSERT INTO market_ingestion_runs
                    (run_id, provider, exchange, status, started_at)
                VALUES (%s, 'tejhq_eod', %s, 'RUNNING', %s)
                """,
                (run_id, market, datetime.now(timezone.utc)),
            )
            return str(run_id), True, "RUNNING"
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("Unable to reserve a persistent market collection run") from error


async def collect_claimed_snapshot(exchange: str, run_id: str) -> dict[str, object]:
    """Fetch, validate and atomically persist a previously claimed collection run."""
    market = exchange.strip().upper()
    try:
        provider_id, trading_date, source_rows = await fetch_latest_snapshot(
            market.lower(), max_lookback_days=14
        )
        normalized = normalize_snapshot_rows(
            source_rows,
            exchange=market,
            trading_date=trading_date,
            provider=provider_id,
            retrieved_at=datetime.now(timezone.utc),
        )
        count = await asyncio.to_thread(
            persist_snapshot,
            normalized,
            run_id=run_id,
            trading_date=trading_date,
            rows_received=len(source_rows),
        )
        return {
            "run_id": run_id,
            "provider": provider_id,
            "exchange": market,
            "trading_date": trading_date.isoformat(),
            "rows_received": len(source_rows),
            "rows_upserted": count,
            "status": "SUCCEEDED",
        }
    except Exception as error:
        try:
            await asyncio.to_thread(
                fail_run, run_id=run_id, code=type(error).__name__, message=str(error)
            )
        except Exception:
            pass
        raise
