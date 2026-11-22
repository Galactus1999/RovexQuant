# Phase 1 — Engineering Foundations: Acceptance Contract

Status: IN PROGRESS
Baseline: `feat/quant-neon-postgres-vault` @ `c7ee58fd718441fede3924297a6cb776a3ab9fc3`
Implementation branch: `feat/quant-phase1-engineering-foundations`
Governance: PR #8 remains open, draft and unmerged. No signing configuration or production secrets may be changed.

This contract freezes the Phase 1 scope. Phase 2 must not begin until every applicable item below has implementation and test evidence. A technically unsupported item must be explicitly excluded with rationale; it cannot be silently marked complete.

## P1-ARCH — Ownership and dependency boundaries
- [x] ARCH-01: Document one authoritative owner for auth, market data, instruments, research/indicators, strategies, backtesting, portfolio/risk, AI, persistence and Android presentation/data.
- [ ] ARCH-02: Define allowed dependency direction and prevent API route handlers from owning domain calculations or provider logic.
- [ ] ARCH-03: Add architecture tests for forbidden dependency directions and duplicate ownership where practical.
- [x] ARCH-04: Keep FastAPI as a modular monolith and Android as one Gradle app module unless measured evidence justifies module extraction.
- [x] ARCH-05: Add versioning/compatibility policy for API and persisted schemas.

## P1-SEC — Authentication, authorization and resource controls
- [x] SEC-01: Add bounded login abuse protection; do not trust forwarded client-IP headers unless the proxy trust chain is explicitly configured.
- [x] SEC-02: Protect research compute endpoints with an explicit documented access policy; no accidental anonymous expensive compute.
- [x] SEC-03: Add per-request body limits and endpoint-level limits for indicator arrays, backtest bars/signals and MCP arguments/responses.
- [x] SEC-04: NSE MCP tools are default-deny: only positively identified read-only tools may be invoked.
- [x] SEC-05: Validate MCP tool arguments against the discovered JSON Schema without allowing remote schema retrieval/SSRF.
- [ ] SEC-06: Ensure auth failures, throttling, oversized payloads and invalid remote schemas return safe structured errors.
- [ ] SEC-07: Add regression tests for every security invariant; secrets must not appear in responses/logs/tests.

## P1-DB — Persistence, migrations and connection management
- [x] DB-01: Hosted mode must fail closed if PostgreSQL persistence is selected but its URL is absent/invalid; SQLite is explicit local/test mode only.
- [x] DB-02: Document and validate separate application pooled URL and migration direct URL; never expose connection strings.
- [x] DB-03: Introduce a versioned migration mechanism with a single migration owner; remove schema creation from ordinary request paths.
- [x] DB-04: Add real disposable PostgreSQL integration tests; driver doubles remain unit tests only.
- [ ] DB-05: Test encryption, round-trip, replacement, expiry, rollback and migration compatibility against PostgreSQL.
- [ ] DB-06: Define backup/restore and key-rotation operational procedures; prove restore in an integration workflow or explicitly gate it on credentials.

## P1-OBS — Diagnostics and observability
- [x] OBS-01: Separate liveness from readiness; readiness checks mandatory dependencies without revealing secrets.
- [x] OBS-02: Add correlation/request IDs across API request boundaries.
- [ ] OBS-03: Add structured, redacted logs; Authorization, admin tokens, provider credentials, request bodies and restricted raw market responses must never be logged.
- [ ] OBS-04: Define provider/job/AI metrics and OpenTelemetry-compatible instrumentation boundaries; implement only metrics with a real source.
- [ ] OBS-05: Test diagnostics for secret leakage and dependency failure semantics.

## P1-ANDROID — Android architecture foundations
- [x] AND-01: Define feature/API/repository/session/stream ownership and package boundaries.
- [x] AND-02: Move network orchestration out of Composable presentation code into repositories and lifecycle-aware state holders/ViewModels where complexity warrants.
- [x] AND-03: Ensure lifecycle-aware state collection, cancellation and session cleanup.
- [x] AND-04: Preserve the single Gradle app module; no wholesale UI rewrite.
- [x] AND-05: Add deterministic authenticated instrumentation setup and execute login/navigation regression tests on a CI emulator.

## P1-CI — Quality gates
- [ ] CI-01: Ruff, Python compile, backend unit/regression tests, Android compile/lint/JVM tests remain required.
- [ ] CI-02: Add security, architecture and API contract tests as required jobs.
- [x] CI-03: Add real PostgreSQL integration service/job.
- [x] CI-04: Add Android emulator instrumentation job with deterministic auth fixture.
- [ ] CI-05: Pin/lock runtime dependencies and review GitHub Action runtime deprecations.
- [ ] CI-06: Keep external credentialed live-provider tests optional and never treat skipped tests as passing evidence.
- [ ] CI-07: Final CI must pass on the Phase 1 branch head; inspect exact job logs and artifacts.

## Phase close evidence
For each requirement record: requirement ID, owning source path, test ID, exact command/workflow run, outcome and any limitation. Phase 1 closes only when all applicable checkboxes are complete and the full regression workflow is green. No merge is authorized by this contract.


## Implementation evidence (22 November 2026)

Implementation branch: `feat/quant-phase1-engineering-foundations`
Draft PR: https://github.com/Galactus1999/RovexQuant/pull/9
PR #8 remains open, draft and unmerged.

Implemented source paths:
- Security controls: `backend/src/rovexquant/security/request_controls.py`
- Request correlation: `backend/src/rovexquant/security/request_context.py`
- API auth/resource gates/readiness: `backend/src/rovexquant/api/main.py`
- NSE default-deny/schema validation and bounded response reads: `backend/src/rovexquant/market_data/nse_mcp.py`
- Hosted storage/TLS/schema validation: `backend/src/rovexquant/market_data/upstox_credentials.py`
- Alembic environment and baseline: `backend/alembic.ini`, `backend/migrations/`
- PostgreSQL integration tests: `backend/tests/test_migrations.py`
- Android data boundaries: `android/app/src/main/java/com/rovexquant/app/data/`
- Android workspace ViewModel: `android/app/src/main/java/com/rovexquant/app/feature/workspace/QuantWorkspaceViewModel.kt`
- Deterministic instrumentation fixture: `android/app/src/androidTest/`

Tests added:
- `test_research_compute_requires_app_session`
- `test_indicator_input_is_bounded`
- `test_request_body_limit_rejects_oversized_payload`
- `test_login_rate_limit_cannot_be_bypassed_with_forwarded_ip_header`
- NSE tool missing-hint, invalid-schema, remote-ref and safe-catalog tests
- hosted storage mode, TLS and schema revision tests
- real PostgreSQL migration, vault encryption, replacement and expiry tests
- Android emulator login failure/retry, sign-out and navigation tests

Evidence already observed on earlier intermediate checkpoints:
- Backend static/pytest checkpoint passed after correcting the legacy NSE fixture.
- A disposable PostgreSQL migration/vault integration job passed.
- Docker startup and credential-vault smoke passed in SQLite test mode.
These results predate the latest Android and final Phase 1 edits. They are not final-head acceptance evidence.

Still open:
- Backend router/use-case extraction and architecture tests.
- Structured redacted logs, metrics/traces and secret-leak tests.
- MCP response-limit test and safe error contract coverage.
- Backup/restore and key-rotation runbook/evidence.
- Durable job contract and explicit current-hosting exclusion.
- Android final-head compilation and actual emulator instrumentation run.
- Dependency lockfile and dependency/licence/secret scans.
- Final full CI on the exact latest branch head.

No production Neon migration or Render deployment was performed. The disposable PostgreSQL job does not prove production Neon state. No live Upstox/Dhan/NSE credentialed connectivity or physical-device validation is claimed.
