"""Bounded request controls for the single-instance private Quant pilot.

The in-process limiter is intentionally not represented as a distributed control.
Before horizontal scaling, replace its storage with an atomic shared limiter and
configure trusted proxy CIDRs rather than trusting arbitrary forwarding headers.
"""
from __future__ import annotations

from collections import OrderedDict, deque
from threading import Lock
from time import monotonic
from typing import Callable
from fastapi import HTTPException, Request
from starlette.responses import JSONResponse
from rovexquant.security.request_context import current_request_id


class SlidingWindowRateLimiter:
    """Thread-safe, bounded, process-local sliding-window limiter."""
    def __init__(self, max_buckets: int = 4096) -> None:
        self._max_buckets = max_buckets
        self._buckets: OrderedDict[str, deque[float]] = OrderedDict()
        self._lock = Lock()

    def clear(self) -> None:
        with self._lock:
            self._buckets.clear()

    def check(self, key: str, limit: int, window_seconds: int) -> int | None:
        now = monotonic()
        with self._lock:
            bucket = self._buckets.get(key)
            if bucket is None:
                if len(self._buckets) >= self._max_buckets:
                    self._buckets.popitem(last=False)
                bucket = deque()
                self._buckets[key] = bucket
            else:
                self._buckets.move_to_end(key)
            cutoff = now - window_seconds
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()
            if len(bucket) >= limit:
                return max(1, int(window_seconds - (now - bucket[0])) + 1)
            bucket.append(now)
            return None


rate_limiter = SlidingWindowRateLimiter()


def rate_limit(bucket: str, *, limit: int, window_seconds: int) -> Callable[[Request], None]:
    """Build a FastAPI dependency keyed by the direct ASGI peer."""
    def enforce(request: Request) -> None:
        peer = request.client.host if request.client is not None else "unknown"
        retry_after = rate_limiter.check(f"{bucket}:{peer}", limit=limit, window_seconds=window_seconds)
        if retry_after is not None:
            raise HTTPException(
                status_code=429,
                detail="Too many requests. Retry after the indicated interval.",
                headers={"Retry-After": str(retry_after)},
            )
    return enforce


def _request_body_error(status: int, code: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"detail": detail, "error": {"code": code, "message": detail}, "request_id": current_request_id()},
    )


class RequestBodyLimitMiddleware:
    """Buffer and cap HTTP request bodies before application parsing."""
    def __init__(self, app, max_body_size: int = 2 * 1024 * 1024) -> None:
        self.app = app
        self.max_body_size = max_body_size

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or scope["method"] in {"GET", "HEAD", "OPTIONS"}:
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        raw_length = headers.get(b"content-length")
        if raw_length is not None:
            try:
                declared_length = int(raw_length)
            except ValueError:
                await _request_body_error(400, "bad_request", "Invalid Content-Length")(scope, receive, send)
                return
            if declared_length < 0:
                await _request_body_error(400, "bad_request", "Invalid Content-Length")(scope, receive, send)
                return
            if declared_length > self.max_body_size:
                await _request_body_error(413, "request_too_large", "Request body exceeds the configured size limit.")(scope, receive, send)
                return
        chunks: list[bytes] = []
        total = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            if message["type"] != "http.request":
                continue
            chunk = message.get("body", b"")
            total += len(chunk)
            if total > self.max_body_size:
                await _request_body_error(413, "request_too_large", "Request body exceeds the configured size limit.")(scope, receive, send)
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        body = b"".join(chunks)
        replayed = False
        async def replay_receive():
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()
        await self.app(scope, replay_receive, send)


login_rate_limit = rate_limit("login", limit=5, window_seconds=60)
indicator_rate_limit = rate_limit("indicators", limit=12, window_seconds=60)
backtest_rate_limit = rate_limit("backtest", limit=5, window_seconds=60)
nse_tool_rate_limit = rate_limit("nse-tool-call", limit=6, window_seconds=60)
ai_request_rate_limit = rate_limit("ai-request", limit=10, window_seconds=60)
