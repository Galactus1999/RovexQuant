#!/usr/bin/env python3
"""Refresh and persist allowlisted macro data sources."""
from __future__ import annotations

import asyncio
import json

from rovexquant.market_data.macro_ingestion import refresh_macro_sources


def main() -> int:
    result = asyncio.run(refresh_macro_sources())
    print(json.dumps(result, sort_keys=True))
    return 0 if result["succeeded"] > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
