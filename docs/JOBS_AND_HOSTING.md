# Quant asynchronous job contract and current hosting boundary

## Current state

No durable background-job API, queue, worker or scheduler is implemented in
Phase 1. Research calculations remain synchronous, bounded HTTP requests.
FastAPI BackgroundTasks are not used as a durable execution mechanism.

The current Render Free deployment is a private development/personal pilot. Its
filesystem is ephemeral and it does not provide a reliable always-on worker.
Do not claim that an in-process task survives a restart, deploy, idle spin-down
or process crash. Do not enqueue work into an in-memory list and call it durable.

## Contract required before exposing long-running work

A future job implementation must persist state before acknowledging submission.
The minimum job record is:

- immutable job ID and owner/tenant ID;
- job type and schema version;
- idempotency key with a unique owner-scoped constraint;
- validated, bounded input reference (not unrestricted raw market payload);
- lifecycle state: QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;
- attempt count, maximum attempts, next retry time;
- lease owner, lease expiry and heartbeat timestamp;
- created, started, updated and completed timestamps;
- safe result reference and bounded error code/message;
- data-source provenance, data-use policy and retention expiry.

## Execution invariants

1. Persist the job and idempotency key in one transaction before returning 202.
2. Repeated submission with the same owner and idempotency key returns the same
   job; it must not duplicate side effects.
3. Workers claim jobs with an atomic lease/compare-and-set operation.
4. Expired leases are recoverable after process death; a second worker may retry
   only after lease expiry and must remain idempotent.
5. Retries are bounded, classified and back off; invalid input and permission
   failures are not retried.
6. Cancellation is persisted and checked at safe boundaries.
7. Result storage follows the source licence/retention policy; restricted raw
   exchange/MCP data is not persisted merely because a job exists.
8. Logs and metrics contain IDs/status/durations only, never job payloads,
   prompts, provider credentials or raw market responses.
9. API status and result endpoints enforce owner authorization on every read.
10. Shutdown and deploy must release or expire leases predictably.

## Hosting gate

Before implementing a worker, select a host with durable PostgreSQL, persistent
job state, a continuously running worker process, restart policy and observability.
A PostgreSQL-backed queue may be sufficient at current scale; Kafka is not
justified without measured throughput/consumer requirements. Do not introduce a
distributed broker as a default dependency.

Until this gate is met, long-running work must remain unavailable or be executed
as an explicitly synchronous bounded request. This is a documented exclusion,
not a completed durable-job feature.
