"""Application boundary for GDELT news discovery."""
from typing import Any

from rovexquant.market_data.gdelt import search_articles


async def discover_articles(query: str, *, timespan: str, limit: int) -> list[dict[str, Any]]:
    return await search_articles(query, timespan=timespan, limit=limit)
