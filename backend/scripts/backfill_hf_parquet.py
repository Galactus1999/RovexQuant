#!/usr/bin/env python3
"""Import one NSE/BSE year from TejHQ's public Parquet archive."""
from __future__ import annotations
import argparse
import json
import sys
from rovexquant.market_data.historical_backfill import backfill_parquet_year

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exchange", required=True, choices=("nse", "bse"))
    parser.add_argument("--year", required=True, type=int)
    args = parser.parse_args()
    try:
        result = backfill_parquet_year(args.exchange, args.year)
    except Exception as error:
        print(json.dumps({"status": "FAILED", "error": str(error)[:240]}), file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
