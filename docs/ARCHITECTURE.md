# RovexQuant architecture and ownership

## Product boundary

RovexQuant is an Android-first Indian-market quantitative research workspace backed by a FastAPI modular monolith. It is research-only: no broker order placement, automated execution or autonomous risk changes.

The initial deployment is a private, single-owner pilot. Multi-user tenancy, always-on streaming and commercial redistribution are separate capabilities requiring explicit security, hosting and data-rights work.

## Current implementation map

| Domain / responsibility | Current authoritative owner | Current state / boundary |
|---|---|---|
| Android application composition and navigation | `RovexQuantApp.kt` | Compose shell; owns top-level session/navigation/stream lifecycle today |
| Android HTTP and WebSocket transport | `MarketDataClient.kt` (`QuantApi`, `MarketStreamController`) | Transport currently lives in the UI package; Phase 1 moves orchestration behind data/repository boundaries without rewriting screens |
| Android session persistence | `QuantSessionStore.kt` | Encrypted session token storage using Android Keystore |
| Backend HTTP composition and route registration | `api/main.py` | Current composition point is oversized and still contains request contracts/orchestration; Phase 1 progressively separates routers and application services |
| App-session authentication | `auth.py` | Short-lived HMAC-signed app session; single configured owner, not a multi-tenant identity system |
| Provider-neutral quote selection | `market_data/service.py` | Explicit provider order and transient-error failover; semantic capability/entitlement compatibility remains to be implemented |
| Upstox and Dhan REST adapters | `market_data/providers.py` | Snapshot quote adapters; real credentialed connectivity is not proven by unit tests |
| Instrument master | `market_data/instruments.py` | Upstox NSE index instrument resolution; Android must fail closed when official mapping is unavailable |
| Upstox streaming | `market_data/streaming.py` | Backend bridge and Protobuf decoder exist; protocol/device live verification is outstanding |
| TejHQ EOD | `market_data/tejhq.py` | Backend-only EOD adapter; not an intraday/live feed |
| NSE MCP | `market_data/nse_mcp.py` | On-demand informational adapter; explicit read-only allow policy and transient/no-persistence boundary |
| Provider credential vault | `market_data/upstox_credentials.py` | Fernet-encrypted Upstox token; PostgreSQL hosted mode, explicit SQLite local/test mode |
| Canonical market-bar domain | `domain/market.py` | Decimal/UUID bar contract exists; quote path is not yet fully normalized to this contract |
| Indicators | `indicators/` | Deterministic calculations; API wrapper currently delegates to these modules |
| Strategies | `strategies/` | Versioned registry and strategy implementations; no complete multi-stock scanner workflow yet |
| Backtesting | `backtesting/engine.py` | Long-only prototype; not a scientifically complete execution simulator |
| AI gateway | `ai/gateway.py` | Server-side chat adapter; no Android integration, per-user BYOK, research workflow or independent verifier yet |
| Database schema/migrations | Not yet established | Credential table is currently bootstrapped by vault code; migration system and durable research schemas are Phase 1 work |
| Jobs/observability | Not yet established | No durable worker/job queue or production-grade distributed tracing/metrics pipeline yet |

## Dependency direction

Target direction:

```text
Android UI
  -> feature state holders / ViewModels
  -> repositories
  -> typed API clients
  -> versioned FastAPI contracts
  -> application use cases
  -> domain services and deterministic engines
  -> provider/storage ports
  -> external adapters / PostgreSQL
```

Backend rules:
- Route handlers validate transport input, authorize, call an application service and map the result to a response. They do not implement indicator formulas, strategy rules, provider protocol logic or portfolio accounting.
- Domain engines do not import FastAPI, Android, provider SDKs or database drivers.
- Provider adapters normalize provider responses and report capabilities/provenance; they do not select strategies or make risk decisions.
- Application services orchestrate domain services and ports. Each domain has one authoritative owner.
- AI is downstream of data-use policy and deterministic calculations. It may explain evidence but cannot authoritatively calculate, change risk controls or place orders.
- Shared infrastructure (auth, logging, persistence, policy) must not absorb domain-specific business logic.

Android rules:
- Composables render state and emit user intent; they do not own network orchestration or long-running calculations.
- Repositories own data-source coordination and cache policy.
- ViewModels/state holders own screen state and cancellation where screen complexity warrants them.
- Collect flows with lifecycle-aware APIs. Cancel stream/network work when the owning session or lifecycle ends.
- Keep one Gradle app module until measured build, test or ownership benefits justify extraction. Do not create a module per screen.

## Market-data contract

The target shared platform supports distinct source modes:
- REST snapshots and historical queries;
- WebSocket streaming;
- batch/file imports and scheduled EOD updates;
- on-demand MCP/tool queries.

These are not forced through one transport pipeline. They converge on canonical instrument identity, typed observation contracts, provenance, quality/freshness and data-use policy.

Every observation should carry instrument identity/mapping, venue/segment, currency/unit, value precision, adjustment basis, event/source/retrieval timestamps, provider, data mode, market session, freshness threshold, quality flags and retention/use policy. Distinguish LIVE, DELAYED, EOD, HISTORICAL, STALE, UNKNOWN and UNAVAILABLE. Missing, zero, null and stale are not interchangeable.

Provider fallback is permitted only when an alternate is explicitly selected and its instrument, venue/segment, data type, adjustment basis, currency, market phase, freshness and entitlement semantics are compatible. Otherwise report a provider mismatch or unavailable state.

Money and ledger values use Decimal/fixed-point semantics. Statistical arrays may use float64 with explicit conversion and precision tests. Adjusted and unadjusted series must never be mixed silently.

## Data rights and retention

Data rights are part of the provider contract, not an afterthought. Each provider/data product must explicitly define display, transient analysis, cache, persistence, derivation, AI context, training, export, sharing/redistribution, commercial use and retention.

NSE MCP is an on-demand informational source. Do not treat it as a licensed commercial feed or unrestricted AI-training corpus. Default-deny uncertain use, do not persist raw responses, and do not send restricted source data to external models without permission. TejHQ's published terms restrict free data to personal research and prohibit raw redistribution; a paid TejHQ plan does not grant exchange rights.

## Persistence and operations

- PostgreSQL/Neon is the durable transactional store for user/configuration/credential metadata and future research records.
- Use Neon's pooled transaction endpoint for ordinary short-lived application requests; use a direct endpoint for schema migrations and operations needing session-level state.
- SQLite is local/test-only and must be selected explicitly. Hosted Docker defaults to PostgreSQL and fails closed if its URL or encryption configuration is absent.
- Migrations have one owner and run as an explicit deployment operation, not during ordinary request handling.
- FastAPI in-process tasks are not durable background jobs. A recoverable job must have persistent state, idempotency and restart semantics before it is exposed as reliable.
- Render Free is a development/personal pilot tier: no durable filesystem and no dependable always-on WebSocket/worker guarantees.

## API and schema compatibility

- Existing public routes remain under `/api/v1`.
- Additive, backward-compatible response fields and optional request fields may be added within v1.
- Removing/renaming fields, changing units/timestamp semantics, or changing required request behaviour is breaking and requires a new API version or an explicit deprecation/migration window.
- Persisted database changes are versioned migrations with an upgrade path and rollback/backup plan.
- Each API contract change requires request/response fixtures and compatibility tests. OpenAPI is generated from the same route/request definitions used by runtime code.

## Research and backtest limitations

The current backtest is a long-only prototype. Do not describe its output as execution-realistic. A credible engine requires point-in-time universes and fundamentals, delisted instruments, corporate actions, event-time fills, realistic liquidity/partial-fill/slippage/market-impact assumptions, Indian date-effective cost schedules, chronological validation, trial-count disclosure and selection-bias controls.

## AI boundary

The current AI gateway is backend-only and uses server-configured credentials. Before Android integration, add per-user authorization, encrypted user credential ownership, quotas/budgets, an allow-listed schema-validated tool registry, data-rights checks, bounded workflows, deterministic quant tools, independent verification and provenance. Never ship a global/admin token in the APK.

## Phase governance

Implementation is governed by `docs/PHASE1_ACCEPTANCE.md`. A phase closes only when each agreed requirement has an owner, test and exact evidence. Unverified external connectivity and skipped device tests remain unverified, not passed.
