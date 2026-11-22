# Phase 1 — Engineering Foundations: Acceptance Contract

Status: IN PROGRESS
Baseline: `feat/quant-neon-postgres-vault` @ `c7ee58fd718441fede3924297a6cb776a3ab9fc3`
Implementation branch: `feat/quant-phase1-engineering-foundations`
Governance: PR #8 remains open, draft and unmerged. No signing configuration or production secrets may be changed.

This contract freezes the Phase 1 scope. Phase 2 must not begin until every applicable item below has implementation and test evidence. A technically unsupported item must be explicitly excluded with rationale; it cannot be silently marked complete.

## P1-ARCH — Ownership and dependency boundaries
- [ ] ARCH-01: Document one authoritative owner for auth, market data, instruments, research/indicators, strategies, backtesting, portfolio/risk, AI, persistence and Android presentation/data.
- [ ] ARCH-02: Define allowed dependency direction and prevent API route handlers from owning domain calculations or provider logic.
- [ ] ARCH-03: Add architecture tests for forbidden dependency directions and duplicate ownership where practical.
- [ ] ARCH-04: Keep FastAPI as a modular monolith and Android as one Gradle app module unless measured evidence justifies module extraction.
- [ ] ARCH-05: Add versioning/compatibility policy for API and persisted schemas.

## P1-SEC — Authentication, authorization and resource controls
- [ ] SEC-01: Add bounded login abuse protection; do not trust forwarded client-IP headers unless the proxy trust chain is explicitly configured.
- [ ] SEC-02: Protect research compute endpoints with an explicit documented access policy; no accidental anonymous expensive compute.
- [ ] SEC-03: Add per-request body limits and endpoint-level limits for indicator arrays, backtest bars/signals and MCP arguments/responses.
- [ ] SEC-04: NSE MCP tools are default-deny: only positively identified read-only tools may be invoked.
- [ ] SEC-05: Validate MCP tool arguments against the discovered JSON Schema without allowing remote schema retrieval/SSRF.
- [ ] SEC-06: Ensure auth failures, throttling, oversized payloads and invalid remote schemas return safe structured errors.
- [ ] SEC-07: Add regression tests for every security invariant; secrets must not appear in responses/logs/tests.

## P1-DB — Persistence, migrations and connection management
- [ ] DB-01: Hosted mode must fail closed if PostgreSQL persistence is selected but its URL is absent/invalid; SQLite is explicit local/test mode only.
- [ ] DB-02: Document and validate separate application pooled URL and migration direct URL; never expose connection strings.
- [ ] DB-03: Introduce a versioned migration mechanism with a single migration owner; remove schema creation from ordinary request paths.
- [ ] DB-04: Add real disposable PostgreSQL integration tests; driver doubles remain unit tests only.
- [ ] DB-05: Test encryption, round-trip, replacement, expiry, rollback and migration compatibility against PostgreSQL.
- [ ] DB-06: Define backup/restore and key-rotation operational procedures; prove restore in an integration workflow or explicitly gate it on credentials.

## P1-OBS — Diagnostics and observability
- [ ] OBS-01: Separate liveness from readiness; readiness checks mandatory dependencies without revealing secrets.
- [ ] OBS-02: Add correlation/request IDs across API request boundaries.
- [ ] OBS-03: Add structured, redacted logs; Authorization, admin tokens, provider credentials, request bodies and restricted raw market responses must never be logged.
- [ ] OBS-04: Define provider/job/AI metrics and OpenTelemetry-compatible instrumentation boundaries; implement only metrics with a real source.
- [ ] OBS-05: Test diagnostics for secret leakage and dependency failure semantics.

## P1-ANDROID — Android architecture foundations
- [ ] AND-01: Define feature/API/repository/session/stream ownership and package boundaries.
- [ ] AND-02: Move network orchestration out of Composable presentation code into repositories and lifecycle-aware state holders/ViewModels where complexity warrants.
- [ ] AND-03: Ensure lifecycle-aware state collection, cancellation and session cleanup.
- [ ] AND-04: Preserve the single Gradle app module; no wholesale UI rewrite.
- [ ] AND-05: Add deterministic authenticated instrumentation setup and execute login/navigation regression tests on a CI emulator.

## P1-CI — Quality gates
- [ ] CI-01: Ruff, Python compile, backend unit/regression tests, Android compile/lint/JVM tests remain required.
- [ ] CI-02: Add security, architecture and API contract tests as required jobs.
- [ ] CI-03: Add real PostgreSQL integration service/job.
- [ ] CI-04: Add Android emulator instrumentation job with deterministic auth fixture.
- [ ] CI-05: Pin/lock runtime dependencies and review GitHub Action runtime deprecations.
- [ ] CI-06: Keep external credentialed live-provider tests optional and never treat skipped tests as passing evidence.
- [ ] CI-07: Final CI must pass on the Phase 1 branch head; inspect exact job logs and artifacts.

## Phase close evidence
For each requirement record: requirement ID, owning source path, test ID, exact command/workflow run, outcome and any limitation. Phase 1 closes only when all applicable checkboxes are complete and the full regression workflow is green. No merge is authorized by this contract.
