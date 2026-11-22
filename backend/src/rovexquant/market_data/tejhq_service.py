"""Application services for the TejHQ EOD provider adapter."""
from datetime import date
from typing import Any

from rovexquant.market_data.tejhq import TejHqEodProvider, TejHqError as TejHqError


def provider_status() -> dict[str, Any]:
    return TejHqEodProvider.from_environment().safe_status()


async def fetch_ohlcv(exchange: str, symbol: str, from_date: date, to_date: date, *, adjusted: bool):
    provider = TejHqEodProvider.from_environment()
    bars = await provider.get_ohlcv(exchange, symbol, from_date, to_date, adjusted=adjusted)
    return provider.provider_id, bars


async def fetch_delivery(exchange: str, symbol: str, from_date: date, to_date: date):
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_delivery(exchange, symbol, from_date, to_date)


async def fetch_snapshot(exchange: str, trading_date: date):
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_snapshot(exchange, trading_date)


async def fetch_actions(symbol: str):
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_actions(symbol)


async def fetch_status():
    provider = TejHqEodProvider.from_environment()
    return provider.provider_id, await provider.get_status()
