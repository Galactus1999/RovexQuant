# P4 — durable macro data and RBI DBIE mirror

## Implemented path

- World Bank India indicator API: keyless, annual official-release cadence.
- FRED: optional server-side API key; skipped cleanly when not configured.
- AMFI NAV: daily mutual-fund NAV only; never treated as an equity quote.
- RBIH DBIE static mirror: read-only delayed snapshot of RBI DBIE data. It is not represented as a live RBI API or as primary-source verification. Every observation carries its mirror URL, retrieved timestamp, data vintage and primary RBI DBIE URL.

Macro observations are stored in PostgreSQL with provider, series, period, unit, frequency, source URL, content hash, retrieval timestamp and metadata. The composite key preserves changed source vintages instead of silently overwriting historical values. Repeated identical payloads are content-addressed and do not duplicate observation rows.

## Automatic refresh

GitHub Actions runs the refresh workflow at 18:00 IST, Monday–Saturday, and supports manual dispatch. It requires the existing database connection secret. FRED_API_KEY is optional. No paid API is required for World Bank, AMFI or the RBIH mirror.

## RBI source limitation

RBI's public DBIE portal is https://data.rbi.org.in/DBIE/. RBIH's DBIE project documents its static JSON payload convention and explicitly describes the data as reflecting the mirror's last scrape, not live data. The adapter uses only three allowlisted series and fails closed if the mirror payload does not contain recognized numeric observations. Verify important readings against the RBI portal.

## Endpoints

- POST /api/v1/app/research/macro/refresh
- GET /api/v1/app/research/macro/history?provider=WORLD_BANK&limit=500
- GET /api/v1/app/research/macro/status?limit=20

All routes require an app session and are rate limited.
