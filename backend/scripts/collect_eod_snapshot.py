"""One-shot idempotent TejHQ NSE/BSE EOD collector; requires migrated PostgreSQL."""
from __future__ import annotations
import argparse
import asyncio
from datetime import datetime, timezone
import sys
from uuid import uuid4

from rovexquant.market_data.eod_store import (
    fail_run, normalize_snapshot_rows, persist_snapshot, start_run,
)
from rovexquant.market_data.tejhq_service import fetch_latest_snapshot


async def collect(exchange: str) -> dict[str, object]:
    market, provider, run_id = exchange.upper(), "tejhq_eod", str(uuid4())
    started = datetime.now(timezone.utc)
    start_run(run_id=run_id, provider=provider, exchange=market, started_at=started)
    try:
        provider_id, trading_date, source_rows = await fetch_latest_snapshot(
            market.lower(), max_lookback_days=14
        )
        normalized = normalize_snapshot_rows(
            source_rows, exchange=market, trading_date=trading_date,
            provider=provider_id, retrieved_at=datetime.now(timezone.utc),
        )
        count = persist_snapshot(
            normalized, run_id=run_id, trading_date=trading_date,
            rows_received=len(source_rows),
        )
        return {
            "run_id": run_id, "provider": provider_id, "exchange": market,
            "trading_date": trading_date.isoformat(), "rows_received": len(source_rows),
            "rows_upserted": count, "status": "SUCCEEDED",
        }
    except Exception as error:
        try:
            fail_run(run_id=run_id, code=type(error).__name__, message=str(error))
        except Exception:
            pass
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exchange", choices=("nse", "bse", "both"), default="both")
    args = parser.parse_args()
    exchanges = ("nse", "bse") if args.exchange == "both" else (args.exchange,)
    for exchange in exchanges:
        result = asyncio.run(collect(exchange))
        print(
            f"{result['status']} provider={result['provider']} exchange={result['exchange']} "
            f"trading_date={result['trading_date']} rows={result['rows_upserted']} "
            f"run_id={result['run_id']}"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"EOD collection failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1) from error
