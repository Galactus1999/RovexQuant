# Macro and event research feeds

## World Bank
Authenticated endpoint: `GET /api/v1/app/research/macro/world-bank`.
The adapter uses the public Indicators API v2 and requires no API key. The
initial allowlist is India GDP growth, CPI inflation, exports, current-account
balance and per-capita energy use. Each series preserves its official code,
country, annual frequency, unit, period, value, source URL and retrieval time.
These are annual observations, not daily market signals.

## FRED / ALFRED
Authenticated endpoint: `GET /api/v1/app/research/macro/fred`.
Configure `FRED_API_KEY` as a backend secret. Without it, the endpoint returns
a clear `configured=false` response and does not block other feeds. The first
allowlist covers US 10-year yields, effective Fed funds, CPI and broad dollar.
FRED observations retain the provider period and official series identifier.
For point-in-time backtests, request ALFRED vintages before treating revised
macro history as historically knowable.

## RBI and AMFI
RBI DBIE and AMFI remain official-source import candidates. Do not scrape
undocumented endpoints or infer machine API support from a downloadable web
report. The next importer should use official published CSV/XLSX/text downloads,
store publication date, observation period, frequency and source URL, and
retain the source file hash. AMFI NAV is fund NAV data, not an equity quote.

## GDELT
Authenticated endpoint: `GET /api/v1/app/intelligence/events`.
GDELT is a discovery index. A story's GDELT seen time is not the publisher's
publication time and neither establishes that a claim is true. Sector impact
classification remains a transparent hypothesis. Material stories must be
corroborated with RBI/SEBI/PIB/company exchange announcements and linked to
the original publisher.

## Data-quality rules
- Never forward-fill annual/monthly observations into daily candles.
- Preserve observation period separately from retrieval timestamp.
- Keep the source URL and provider identity with every displayed series.
- Missing or unconfigured sources are explicit states, not zero values.
- Do not present this data as a calibrated forecast or an investment signal.
