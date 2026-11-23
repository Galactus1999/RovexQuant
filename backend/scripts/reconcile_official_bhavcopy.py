#!/usr/bin/env python3
"""Compare stored TejHQ EOD bars with an official NSE/BSE UDiFF bhavcopy."""
from __future__ import annotations
import argparse
import asyncio
import json
import sys
from datetime import date
from rovexquant.market_data.official_bhavcopy import reconcile_exchange_date

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exchange", required=True, choices=("nse", "bse"))
    parser.add_argument("--date", required=True, type=date.fromisoformat)
    args = parser.parse_args()
    try:
        result = asyncio.run(reconcile_exchange_date(args.exchange, args.date))
    except Exception as error:
        print(json.dumps({"status": "FAILED", "exchange": args.exchange.upper(),
                          "trading_date": args.date.isoformat(), "error": str(error)[:240]}),
              file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] in {"MATCHED", "MISMATCHED", "NO_REFERENCE_DATA"} else 1

if __name__ == "__main__":
    raise SystemExit(main())
