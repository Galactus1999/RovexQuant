"""Upstox Market Data Feed V3 WebSocket bridge with server-side Protobuf decoding."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import os
import re
import uuid

import httpx
import websockets

from rovexquant.market_data import upstox_market_data_pb2


_INSTRUMENT_KEY = re.compile(r"^NSE_INDEX\\|[A-Za-z0-9 ]{1,80}$")


class StreamError(Exception):
    pass


def decode_feed_message(payload: bytes) -> dict[str, object]:
    response = upstox_market_data_pb2.FeedResponse()
    response.ParseFromString(payload)
    received_at = datetime.now(timezone.utc)
    quotes: list[dict[str, object]] = []
    for instrument_key, feed in response.feeds.items():
        kind = feed.WhichOneof("FeedUnion")
        ltpc = None
        if kind == "ltpc":
            ltpc = feed.ltpc
        elif kind == "fullFeed":
            full_kind = feed.fullFeed.WhichOneof("FullFeedUnion")
            if full_kind == "indexFF":
                ltpc = feed.fullFeed.indexFF.ltpc
            elif full_kind == "marketFF":
                ltpc = feed.fullFeed.marketFF.ltpc
        elif kind == "firstLevelWithGreeks":
            ltpc = feed.firstLevelWithGreeks.ltpc
        if ltpc is None or ltpc.ltp <= 0:
            continue
        exchange_time = datetime.fromtimestamp(ltpc.ltt / 1000, tz=timezone.utc) if ltpc.ltt > 0 else None
        quality = "TIMESTAMP_UNAVAILABLE" if exchange_time is None else "VALID"
        if exchange_time is not None:
            age = (received_at - exchange_time).total_seconds()
            if age < -5:
                quality = "FUTURE_TIMESTAMP"
            elif age > 30:
                quality = "STALE"
        previous = float(ltpc.cp) if ltpc.cp > 0 else None
        change = float(ltpc.ltp - previous) if previous is not None else None
        quotes.append({
            "instrument_key": instrument_key,
            "last_price": float(ltpc.ltp),
            "previous_close": previous,
            "net_change": change,
            "change_percent": (change / previous * 100) if change is not None and previous else None,
            "last_trade_quantity": int(ltpc.ltq),
            "exchange_timestamp": exchange_time.isoformat() if exchange_time else None,
            "received_at": received_at.isoformat(),
            "quality": quality,
        })
    return {
        "type": "market_info" if response.type == upstox_market_data_pb2.market_info else (
            "initial_feed" if response.type == upstox_market_data_pb2.initial_feed else "live_feed"
        ),
        "current_timestamp": response.currentTs,
        "quotes": quotes,
    }


async def stream_upstox_market_data(client_websocket, instrument_keys: list[str], mode: str = "full") -> None:
    token = None
    from rovexquant.market_data.upstox_credentials import get_upstox_analytics_token
    token = get_upstox_analytics_token() or os.getenv("UPSTOX_ANALYTICS_TOKEN", "").strip()
    if not token:
        raise StreamError("Upstox Analytics Token is not configured")
    if not instrument_keys or len(instrument_keys) > 100:
        raise StreamError("Subscribe to between 1 and 100 NSE index instruments")
    if any(not _INSTRUMENT_KEY.fullmatch(key) for key in instrument_keys):
        raise StreamError("Only NSE index instrument keys are allowed on this stream")
    if mode not in {"ltpc", "full"}:
        raise StreamError("Unsupported stream mode")

    async with httpx.AsyncClient(timeout=httpx.Timeout(12.0, connect=5.0)) as client:
        response = await client.get(
            "https://api.upstox.com/v3/feed/market-data-feed/authorize",
            headers={"Authorization": f"Bearer {token}", "Accept": "*/*"},
        )
    if response.status_code != 200:
        raise StreamError(f"Upstox stream authorization failed (HTTP {response.status_code})")
    try:
        authorized_uri = response.json()["data"]["authorized_redirect_uri"]
    except (ValueError, KeyError, TypeError) as error:
        raise StreamError("Upstox did not return an authorized stream URI") from error
    if not isinstance(authorized_uri, str) or not authorized_uri.startswith("wss://"):
        raise StreamError("Upstox returned an invalid secure stream URI")

    async with websockets.connect(
        authorized_uri, open_timeout=12, ping_interval=20, ping_timeout=20,
        close_timeout=5, max_size=1_048_576,
    ) as upstream:
        await upstream.send(__import__("json").dumps({
            "guid": uuid.uuid4().hex,
            "method": "sub",
            "data": {"mode": mode, "instrumentKeys": instrument_keys},
        }))
        async def forward_upstream() -> None:
            async for message in upstream:
                if isinstance(message, bytes):
                    await client_websocket.send_json(decode_feed_message(message))
                else:
                    await client_websocket.send_json({"type": "upstream_text", "message": message[:2000]})

        async def watch_client() -> None:
            while True:
                incoming = await client_websocket.receive()
                if incoming.get("type") == "websocket.disconnect":
                    return
                if incoming.get("text"):
                    # Dynamic subscriptions are deliberately not accepted in this first version.
                    await client_websocket.send_json({"type": "error", "message": "Reconnect to change subscriptions"})
                    return

        tasks = [asyncio.create_task(forward_upstream()), asyncio.create_task(watch_client())]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        for task in done:
            exception = task.exception()
            if exception:
                raise exception
