"""Shared TejHQ EOD collection and durable PostgreSQL job queue."""
from __future__ import annotations

import asyncio
import re
from datetime import datetime, timezone
from uuid import uuid4

import psycopg

from rovexquant.market_data.eod_store import (
    EodStoreError, connect, fail_run, normalize_snapshot_rows, persist_snapshot,
)
from rovexquant.market_data.tejhq_service import fetch_latest_snapshot

LEASE_SECONDS = 300
MAX_ATTEMPTS = 3


def enqueue_ingestion_run(exchange: str, *, force: bool = False) -> tuple[str, bool, str]:
    """Atomically create a run and durable queue row, or join/cool down an existing run."""
    market = exchange.strip().upper()
    if market not in {"NSE", "BSE"}:
        raise EodStoreError("Exchange must be NSE or BSE", 422)
    try:
        with connect() as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"rovexquant:eod:{market}",),
            )
            connection.execute("""
                UPDATE market_ingestion_runs r
                SET status='FAILED', completed_at=NOW(), error_code='STALE_RUN',
                    error_message='Ingestion worker lease expired'
                WHERE r.exchange=%s AND r.status='RUNNING'
                  AND r.started_at < NOW() - INTERVAL '30 minutes'
                  AND NOT EXISTS (
                    SELECT 1 FROM market_ingestion_jobs j
                    WHERE j.run_id=r.run_id AND j.status IN ('QUEUED','RUNNING')
                  )
            """, (market,))
            active = connection.execute("""
                SELECT r.run_id FROM market_ingestion_runs r
                JOIN market_ingestion_jobs j ON j.run_id=r.run_id
                WHERE r.exchange=%s AND r.status='RUNNING'
                  AND j.status IN ('QUEUED','RUNNING')
                ORDER BY r.started_at DESC LIMIT 1
            """, (market,)).fetchone()
            if active:
                return str(active[0]), False, "RUNNING"
            if not force:
                recent = connection.execute("""
                    SELECT run_id FROM market_ingestion_runs
                    WHERE exchange=%s AND status='SUCCEEDED'
                      AND completed_at >= NOW() - INTERVAL '6 hours'
                    ORDER BY completed_at DESC LIMIT 1
                """, (market,)).fetchone()
                if recent:
                    return str(recent[0]), False, "SUCCEEDED"
            run_id = uuid4()
            now = datetime.now(timezone.utc)
            connection.execute("""
                INSERT INTO market_ingestion_runs
                    (run_id, provider, exchange, status, started_at)
                VALUES (%s, 'tejhq_eod', %s, 'RUNNING', %s)
            """, (run_id, market, now))
            connection.execute("""
                INSERT INTO market_ingestion_jobs (run_id, exchange, status)
                VALUES (%s, %s, 'QUEUED')
            """, (run_id, market))
            return str(run_id), True, "QUEUED"
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("Unable to enqueue a persistent market collection job") from error


def claim_next_ingestion_job(worker_id: str) -> dict[str, str] | None:
    """Claim one queued or expired leased job without blocking other workers."""
    with connect() as connection:
        # Reconcile the crash window where bar persistence committed but the
        # worker died before acknowledging the queue item.
        connection.execute("""
            UPDATE market_ingestion_jobs j
            SET status='SUCCEEDED', lease_owner=NULL, lease_expires_at=NULL,
                updated_at=NOW()
            FROM market_ingestion_runs r
            WHERE r.run_id=j.run_id AND r.status='SUCCEEDED'
              AND j.status IN ('QUEUED','RUNNING')
        """)
        # Exhausted leases are terminal; do not leave RUNNING rows forever.
        exhausted = connection.execute("""
            UPDATE market_ingestion_jobs
            SET status='FAILED', lease_owner=NULL, lease_expires_at=NULL,
                last_error='Worker lease expired after maximum attempts', updated_at=NOW()
            WHERE status='RUNNING' AND lease_expires_at <= NOW()
              AND attempt_count >= %s
            RETURNING run_id
        """, (MAX_ATTEMPTS,)).fetchall()
        for item in exhausted:
            connection.execute("""
                UPDATE market_ingestion_runs
                SET status='FAILED', completed_at=NOW(), error_code='WORKER_LEASE_EXHAUSTED',
                    error_message='Worker lease expired after maximum attempts'
                WHERE run_id=%s AND status='RUNNING'
            """, (item[0],))
        row = connection.execute("""
            SELECT run_id, exchange
            FROM market_ingestion_jobs
            WHERE (status='QUEUED' AND available_at <= NOW())
               OR (status='RUNNING' AND lease_expires_at <= NOW() AND attempt_count < %s)
            ORDER BY available_at, created_at
            LIMIT 1 FOR UPDATE SKIP LOCKED
        """, (MAX_ATTEMPTS,)).fetchone()
        if row is None:
            return None
        run_id, exchange = str(row[0]), str(row[1])
        connection.execute("""
            UPDATE market_ingestion_jobs
            SET status='RUNNING', attempt_count=attempt_count+1, lease_owner=%s,
                lease_expires_at=NOW() + (%s * INTERVAL '1 second'),
                heartbeat_at=NOW(), updated_at=NOW()
            WHERE run_id=%s
        """, (worker_id, LEASE_SECONDS, run_id))
        return {"run_id": run_id, "exchange": exchange}


def renew_ingestion_lease(run_id: str, worker_id: str) -> bool:
    with connect() as connection:
        result = connection.execute("""
            UPDATE market_ingestion_jobs
            SET heartbeat_at=NOW(),
                lease_expires_at=NOW() + (%s * INTERVAL '1 second'),
                updated_at=NOW()
            WHERE run_id=%s AND status='RUNNING' AND lease_owner=%s
        """, (LEASE_SECONDS, run_id, worker_id))
        return result.rowcount == 1


def finish_ingestion_job(run_id: str, worker_id: str) -> None:
    with connect() as connection:
        result = connection.execute("""
            UPDATE market_ingestion_jobs
            SET status='SUCCEEDED', lease_owner=NULL, lease_expires_at=NULL,
                updated_at=NOW(), last_error=NULL
            WHERE run_id=%s AND status='RUNNING' AND lease_owner=%s
        """, (run_id, worker_id))
        if result.rowcount != 1:
            raise EodStoreError("Ingestion worker lost its lease before completion")


def retry_or_fail_ingestion_job(
    run_id: str, worker_id: str, error: Exception, *, retryable: bool,
) -> str:
    safe = str(error).replace("\n", " ")[:240]
    with connect() as connection:
        row = connection.execute("""
            SELECT attempt_count FROM market_ingestion_jobs
            WHERE run_id=%s AND status='RUNNING' AND lease_owner=%s
            FOR UPDATE
        """, (run_id, worker_id)).fetchone()
        if row is None:
            return "LEASE_LOST"
        attempts = int(row[0])
        if retryable and attempts < MAX_ATTEMPTS:
            delay = min(60 * (2 ** (attempts - 1)), 600)
            connection.execute("""
                UPDATE market_ingestion_jobs
                SET status='QUEUED', available_at=NOW() + (%s * INTERVAL '1 second'),
                    lease_owner=NULL, lease_expires_at=NULL, heartbeat_at=NOW(),
                    last_error=%s, updated_at=NOW()
                WHERE run_id=%s
            """, (delay, safe, run_id))
            return "RETRY_QUEUED"
        connection.execute("""
            UPDATE market_ingestion_jobs
            SET status='FAILED', lease_owner=NULL, lease_expires_at=NULL,
                last_error=%s, updated_at=NOW()
            WHERE run_id=%s
        """, (safe, run_id))
    fail_run(run_id=run_id, code=type(error).__name__, message=safe)
    return "FAILED"


def claim_ingestion_run(exchange: str, *, force: bool = False) -> tuple[str, bool, str]:
    """Legacy synchronous collector reservation used by the scheduled CLI."""
    market = exchange.strip().upper()
    if market not in {"NSE", "BSE"}:
        raise EodStoreError("Exchange must be NSE or BSE", 422)
    try:
        with connect() as connection:
            connection.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                (f"rovexquant:eod:{market}",),
            )
            connection.execute("""
                UPDATE market_ingestion_runs
                SET status='FAILED', completed_at=NOW(), error_code='STALE_RUN',
                    error_message='Collection lease expired before completion'
                WHERE exchange=%s AND status='RUNNING'
                  AND started_at < NOW() - INTERVAL '30 minutes'
            """, (market,))
            running = connection.execute("""
                SELECT run_id FROM market_ingestion_runs
                WHERE exchange=%s AND status='RUNNING'
                ORDER BY started_at DESC LIMIT 1
            """, (market,)).fetchone()
            if running:
                return str(running[0]), False, "RUNNING"
            if not force:
                recent = connection.execute("""
                    SELECT run_id FROM market_ingestion_runs
                    WHERE exchange=%s AND status='SUCCEEDED'
                      AND completed_at >= NOW() - INTERVAL '6 hours'
                    ORDER BY completed_at DESC LIMIT 1
                """, (market,)).fetchone()
                if recent:
                    return str(recent[0]), False, "SUCCEEDED"
            run_id = uuid4()
            connection.execute("""
                INSERT INTO market_ingestion_runs
                    (run_id, provider, exchange, status, started_at)
                VALUES (%s, 'tejhq_eod', %s, 'RUNNING', %s)
            """, (run_id, market, datetime.now(timezone.utc)))
            return str(run_id), True, "RUNNING"
    except EodStoreError:
        raise
    except psycopg.Error as error:
        raise EodStoreError("Unable to reserve a persistent market collection run") from error


async def collect_claimed_snapshot(
    exchange: str, run_id: str, *, mark_failed: bool = True,
) -> dict[str, object]:
    """Fetch, validate and atomically persist a previously claimed collection run."""
    market = exchange.strip().upper()
    try:
        provider_id, trading_date, source_rows = await fetch_latest_snapshot(
            market.lower(), max_lookback_days=14
        )
        normalized = normalize_snapshot_rows(
            source_rows, exchange=market, trading_date=trading_date,
            provider=provider_id, retrieved_at=datetime.now(timezone.utc),
        )
        count = await asyncio.to_thread(
            persist_snapshot, normalized, run_id=run_id,
            trading_date=trading_date, rows_received=len(source_rows),
        )
        return {
            "run_id": run_id, "provider": provider_id, "exchange": market,
            "trading_date": trading_date.isoformat(), "rows_received": len(source_rows),
            "rows_upserted": count, "status": "SUCCEEDED",
        }
    except Exception as error:
        if mark_failed:
            try:
                await asyncio.to_thread(
                    fail_run, run_id=run_id, code=type(error).__name__, message=str(error)
                )
            except Exception:
                pass
        raise
