# Architecture

Independent, provider-agnostic Indian-market research and paper-trading application. No order placement or broker execution endpoints.

## Modes
- Zero-budget/local mode: Android client, local FastAPI, local historical CSV import and deterministic research engine.
- Personal hosted/development mode: one owner's provider credentials held in backend secret storage; no provider secret or shared gateway token in the APK.
- Multi-user later: per-user identity, tenant-scoped provider connections, encrypted credentials, quotas and audit controls. Do not share a personal broker token across tenants.
- Hosted production: authenticated API, persistent worker and PostgreSQL. Free tiers are not assumed always-on.

## Market-data architecture
- Domain contracts are provider-neutral. Provider instrument identifiers are mapped independently; Upstox instrument keys and Dhan exchange-segment/security-ID pairs are never interchangeable.
- Upstox adapter currently consumes Full Market Quotes V3 REST snapshots. Dhan adapter consumes its market quote REST endpoint as an optional secondary provider.
- Provider order is explicitly requested. Fallback is limited to transient network, timeout, rate-limit and server failures. Invalid credentials and invalid instrument mappings fail closed rather than silently switching accounts.
- Every normalized quote records provider, exchange timestamp when supplied, receive timestamp and quality. Missing, stale, future or invalid-price data must not be called live or generate signals.
- REST snapshots are not WebSocket streams. The V3 Protobuf WebSocket consumer, reconnect/subscription lifecycle and bounded fan-out are a separate phase.
- Sector index return must come from the official index instrument. Constituents are separately required for advances/declines and relative volume; never substitute a simple equal-weighted constituent return for an official index return.
- Public multi-user distribution requires provider approval and applicable exchange-data licensing. Individual API access does not imply redistribution rights.

## Data safety
Persist provider, exchange timestamp, receive timestamp, completeness and quality. Stale, incomplete, gap-detected, disconnected or adjustment-pending data suppresses signals. Delayed data requires explicit policy permission and must never be labelled live.

## Research controls
Point-in-time fundamentals and classifications; delisted securities retained; corporate actions adjusted; no future data; realistic spread, slippage, fees, partial fills, liquidity and circuits. Current backtest engine is long-only and does not model market impact, partial fills or exchange-specific taxes/levies; treat results as prototype output.

## AI boundary
AI receives computed evidence and may explain it. It cannot create prices, override risk gates, set quantity or place orders. Secrets never enter source control.
