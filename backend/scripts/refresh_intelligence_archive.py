#!/usr/bin/env python3
"""Scheduled global-event discovery and durable archive refresh."""
from __future__ import annotations
import asyncio
import json
from rovexquant.market_data.intelligence_archive import run_scheduled_intelligence_refresh


def main() -> int:
    result = asyncio.run(run_scheduled_intelligence_refresh())
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("archive_status") == "SUCCEEDED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
