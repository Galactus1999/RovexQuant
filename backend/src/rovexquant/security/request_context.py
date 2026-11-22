"""Server-owned request correlation, OpenTelemetry spans, redacted logs and process metrics."""
from __future__ import annotations

from collections import defaultdict
from contextvars import ContextVar
import json
import logging
from threading import Lock
from time import perf_counter
from uuid import uuid4

from opentelemetry import trace
from opentelemetry.trace import SpanKind, Status, StatusCode, format_trace_id
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

_request_id: ContextVar[str] = ContextVar("quant_request_id", default="-")
_logger = logging.getLogger("rovexquant.request")
_tracer = trace.get_tracer("rovexquant.http")
_trace_context_propagator = TraceContextTextMapPropagator()


def current_request_id() -> str:
    """Return the server-generated ID for the current request context."""
    return _request_id.get()


def _extract_trace_context(scope):
    """Extract only W3C trace context; never use it for authorization or identity."""
    carrier: dict[str, str] = {}
    for key, value in scope.get("headers", []):
        if key.lower() in {b"traceparent", b"tracestate"}:
            try:
                carrier[key.decode("ascii")] = value.decode("ascii")
            except UnicodeDecodeError:
                continue
    return _trace_context_propagator.extract(carrier)


class RequestMetrics:
    """Bounded-cardinality, process-local HTTP metrics with no sensitive labels."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._counts: dict[tuple[str, str, int], int] = defaultdict(int)
        self._durations: dict[tuple[str, str], list[float | int]] = defaultdict(lambda: [0, 0.0, 0.0])

    def record(self, method: str, route: str, status: int, duration_ms: float) -> None:
        with self._lock:
            self._counts[(method, route, status)] += 1
            values = self._durations[(method, route)]
            values[0] += 1
            values[1] += duration_ms
            values[2] = max(float(values[2]), duration_ms)

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            requests = [
                {"method": method, "route": route, "status": status, "count": count}
                for (method, route, status), count in sorted(self._counts.items())
            ]
            durations = [
                {
                    "method": method,
                    "route": route,
                    "count": int(values[0]),
                    "sum_ms": round(float(values[1]), 3),
                    "max_ms": round(float(values[2]), 3),
                }
                for (method, route), values in sorted(self._durations.items())
            ]
        return {"requests": requests, "durations": durations, "scope": "single_process"}

    def clear(self) -> None:
        with self._lock:
            self._counts.clear()
            self._durations.clear()


request_metrics = RequestMetrics()


class RequestContextMiddleware:
    """Create a server request ID, W3C-compatible server span and redacted log."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        request_token = _request_id.set(request_id)
        started = perf_counter()
        status_code = 500
        error_type: str | None = None
        method = str(scope.get("method", "UNKNOWN"))

        async def send_with_metadata(message):
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = int(message.get("status", 500))
                headers = [
                    (key, value)
                    for key, value in message.get("headers", [])
                    if key.lower() != b"x-request-id"
                ]
                headers.append((b"x-request-id", request_id.encode("ascii")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            with _tracer.start_as_current_span(
                "http.server",
                context=_extract_trace_context(scope),
                kind=SpanKind.SERVER,
                attributes={"http.request.method": method, "quant.request_id": request_id},
                record_exception=False,
                set_status_on_exception=False,
            ) as span:
                try:
                    await self.app(scope, receive, send_with_metadata)
                except Exception as error:
                    error_type = type(error).__name__
                    span.set_status(Status(StatusCode.ERROR))
                    span.set_attribute("error.type", error_type)
                    raise
                finally:
                    duration_ms = (perf_counter() - started) * 1000
                    route_object = scope.get("route")
                    route = getattr(route_object, "path", "__unmatched__")
                    if not isinstance(route, str) or not route.startswith("/"):
                        route = "__unmatched__"
                    request_metrics.record(method, route, status_code, duration_ms)
                    span.set_attribute("http.route", route)
                    span.set_attribute("http.response.status_code", status_code)
                    if status_code >= 500:
                        span.set_status(Status(StatusCode.ERROR))

                    event = {
                        "event": "http_request",
                        "request_id": request_id,
                        "method": method,
                        "route": route,
                        "status_code": status_code,
                        "duration_ms": round(duration_ms, 3),
                    }
                    span_context = span.get_span_context()
                    if span_context.is_valid:
                        event["trace_id"] = format_trace_id(span_context.trace_id)
                    if error_type:
                        event["error_type"] = error_type
                    line = json.dumps(event, separators=(",", ":"), sort_keys=True)
                    if status_code >= 500:
                        _logger.error(line)
                    elif status_code >= 400:
                        _logger.warning(line)
                    else:
                        _logger.info(line)
        finally:
            _request_id.reset(request_token)
