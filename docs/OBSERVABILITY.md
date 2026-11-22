# Quant observability and operational diagnostics

## Current implementation

The API emits one JSON log event per HTTP request through
`rovexquant.security.request_context.RequestContextMiddleware`.

Fields:
- `event`: fixed value `http_request`
- `request_id`: server-generated 128-bit hexadecimal correlation identifier, also returned as `X-Request-ID`
- `method`, `route`: HTTP method and matched route template (not the raw URL)
- `status_code`, `duration_ms`
- `error_type`: exception class only when an unhandled exception escapes the app

The logger deliberately excludes headers, query strings, request/response bodies, provider payloads, authorization values, admin tokens, database URLs, credential ciphertext and exception messages. Do not add payload logging to middleware or adapters.

HTTP metrics are real process-local counters and latency aggregates:
- request count by method, route template and status code;
- duration count, sum and maximum by method and route template.

They are exposed at `GET /api/v1/health/metrics` only when the configured
`X-Quant-Admin-Key` is valid. The endpoint returns `scope=single_process`.
Counters reset on process restart and are not distributed or durable. They are
operational diagnostics for the current single-instance pilot, not a monitoring
system or an SLA source.

Safe HTTP errors include `detail`, `error.code`, `error.message` and
`request_id`. Validation responses omit Pydantic's raw `input` and context
objects. Request-body limit failures use the same safe envelope. Preserve
`Retry-After` for throttling.

## Instrumentation boundaries

Do not invent provider, AI or job metrics without a real event source. When those
systems are instrumented, add metrics at their authoritative service/adapter
boundaries:

| Domain | Candidate metric | Authoritative source |
|---|---|---|
| Provider REST | request count, latency, status, timeout | Provider adapter |
| Market stream | connect/disconnect, decode failures, tick age, sequence gaps | Stream bridge / decoder |
| Data quality | stale observations, missing mappings, incompatible fallback rejected | Canonical data normalizer |
| AI gateway | request count, latency, provider failures, input/output tokens and cost where returned | AI gateway response and provider usage metadata |
| Jobs (future) | queued/running/succeeded/failed/retried age and duration | Durable job state transitions |
| Database | connection failures, query latency, migration revision | Persistence adapter / PostgreSQL driver |

Metric labels must be low-cardinality. Never label metrics with symbols, account
IDs, credentials, prompt text, tool arguments, raw URLs or unrestricted provider
names supplied by a client.

## OpenTelemetry-compatible tracing plan

The current phase does not add an OpenTelemetry SDK/exporter or pretend that
traces are available. The intended span boundaries are:
1. FastAPI server request, carrying W3C trace context and Quant request ID.
2. Application use case (research calculation, market-data query, AI request).
3. Database operation and provider HTTP/WebSocket operation.
4. Future durable job execution, linked to the originating request where possible.

Propagate trace context across trusted service boundaries only. Span attributes
must be allow-listed and payload-free. Redact Authorization, X-Quant-Admin-Key,
provider tokens, database URLs, prompts, raw market data and tool arguments.

## Hosting and scaling limits

The current limiter and metrics are process-local. Keep the pilot to one backend
instance. Multiple workers or replicas require an atomic shared rate limiter and
a metrics backend/collector before horizontal scaling. Render Free does not
provide durable worker execution or a persistent filesystem. FastAPI
BackgroundTasks must not be advertised as durable jobs.


## OpenTelemetry-compatible request tracing

The HTTP boundary uses the OpenTelemetry API and W3C Trace Context propagator.
Each request creates an `http.server` span with only allow-listed attributes:
method, matched route template, response status and server-generated request ID.
Incoming `traceparent`/`tracestate` values are parsed as correlation context
only; they are never trusted for authentication, tenancy or authorization.
The structured request log includes the trace ID when a valid span context is
available. Raw URLs, query strings, bodies, headers, credentials and provider
payloads are excluded from span attributes.

The API package alone supplies no-op spans unless the host installs and configures
an OpenTelemetry SDK/exporter. No trace export or external collector is claimed
for the current Render pilot. This keeps the application instrumented without
forcing a collector or exporter into the current hosting plan.
