"""PostgreSQL-backed EOD ingestion worker with leases and crash recovery."""
from __future__ import annotations

import asyncio
import logging
import os
from uuid import uuid4

from rovexquant.market_data.eod_ingestion import (
    LEASE_SECONDS, claim_next_ingestion_job, collect_claimed_snapshot,
    finish_ingestion_job, renew_ingestion_lease, retry_or_fail_ingestion_job,
)

logger = logging.getLogger(__name__)
POLL_SECONDS = 3
HEARTBEAT_SECONDS = 60


async def _heartbeat(run_id: str, worker_id: str, lost: asyncio.Event) -> None:
    while True:
        await asyncio.sleep(HEARTBEAT_SECONDS)
        try:
            renewed = await asyncio.to_thread(renew_ingestion_lease, run_id, worker_id)
        except Exception:
            logger.exception("Market ingestion lease renewal failed")
            continue
        if not renewed:
            lost.set()
            return


async def _execute(job: dict[str, str], worker_id: str) -> None:
    run_id, exchange = job["run_id"], job["exchange"]
    lost = asyncio.Event()
    heartbeat = asyncio.create_task(_heartbeat(run_id, worker_id, lost))
    try:
        await collect_claimed_snapshot(exchange, run_id, mark_failed=False)
        if lost.is_set():
            logger.warning("Discarding completion acknowledgement after lease loss for run %s", run_id)
            return
        await asyncio.to_thread(finish_ingestion_job, run_id, worker_id)
        logger.info("Market EOD job completed: exchange=%s run_id=%s", exchange, run_id)
    except asyncio.CancelledError:
        raise
    except Exception as error:
        retryable = bool(getattr(error, "retryable", False))
        try:
            result = await asyncio.to_thread(
                retry_or_fail_ingestion_job, run_id, worker_id, error, retryable=retryable
            )
            logger.warning(
                "Market EOD job ended: exchange=%s run_id=%s result=%s error=%s",
                exchange, run_id, result, type(error).__name__,
            )
        except Exception:
            logger.exception("Unable to record market ingestion job failure for %s", run_id)
    finally:
        heartbeat.cancel()
        await asyncio.gather(heartbeat, return_exceptions=True)


async def run_ingestion_worker() -> None:
    """Poll durable jobs; multiple API instances safely share the same queue."""
    worker_id = f"api-worker-{uuid4()}"
    logger.info("Market ingestion worker started: %s", worker_id)
    while True:
        try:
            job = await asyncio.to_thread(claim_next_ingestion_job, worker_id)
            if job is None:
                await asyncio.sleep(POLL_SECONDS)
                continue
            await _execute(job, worker_id)
        except asyncio.CancelledError:
            raise
        except Exception:
            # A transient database outage must not terminate the worker task.
            logger.exception("Market ingestion worker poll failed")
            await asyncio.sleep(max(POLL_SECONDS, min(LEASE_SECONDS // 10, 30)))


def worker_enabled() -> bool:
    mode = os.getenv("QUANT_TOKEN_STORAGE_MODE", "").strip().lower()
    return mode == "postgresql" or (
        not mode and bool(os.getenv("QUANT_TOKEN_DATABASE_URL", "").strip())
    )
