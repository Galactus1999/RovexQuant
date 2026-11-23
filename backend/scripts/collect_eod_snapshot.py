"""One-shot idempotent TejHQ NSE/BSE EOD collector; requires migrated PostgreSQL."""
from __future__ import annotations

import argparse
import asyncio
import sys

from rovexquant.market_data.eod_ingestion import (
    claim_ingestion_run,
    collect_claimed_snapshot,
)


async def collect(exchange: str) -> dict[str, object]:
    market = exchange.upper()
    run_id, started, status = claim_ingestion_run(market, force=True)
    if not started:
        raise RuntimeError(
            f"Another {market} market collection is already active ({status}, run {run_id})"
        )
    return await collect_claimed_snapshot(market, run_id)


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
