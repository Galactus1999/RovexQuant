"""Correlation identifiers for API request diagnostics."""
from __future__ import annotations

from contextvars import ContextVar
from uuid import uuid4


_request_id: ContextVar[str] = ContextVar("quant_request_id", default="-")


def current_request_id() -> str:
    """Return the generated ID for the current request context, if any."""
    return _request_id.get()


class RequestContextMiddleware:
    """Generate a server-owned request ID and expose it in response metadata."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        token = _request_id.set(request_id)

        async def send_with_request_id(message):
            if message["type"] == "http.response.start":
                headers = [
                    (key, value)
                    for key, value in message.get("headers", [])
                    if key.lower() != b"x-request-id"
                ]
                headers.append((b"x-request-id", request_id.encode("ascii")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_request_id)
        finally:
            _request_id.reset(token)
