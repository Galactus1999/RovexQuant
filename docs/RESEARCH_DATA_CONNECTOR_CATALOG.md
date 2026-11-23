# RovexQuant — Free Research Data Connector Catalog

Research snapshot: 2026-11-23. Software licences and data-use terms are separate. Free does not mean unrestricted redistribution or production display rights.

## Integration principles
1. Keep the existing backend as the only ingestion boundary. Android consumes normalized RovexQuant APIs; it never scrapes exchanges or exposes provider credentials.
2. Every observation stores provider, instrument/source identifier, exchange/country, observation timestamp, retrieval timestamp, timezone, currency, unit, frequency, delay class, adjustment status and quality status.
3. EOD is a first-class product mode. Never render EOD as live; expose as-of dates and stale/late status.
4. Cache immutable history; use ETags where available; apply per-provider quotas, retry with jitter, circuit breakers and last-good-data retention.
5. Keep adapters independent so one provider failure cannot block other source families.
6. Preserve raw-source hashes and normalized rows for reproducibility. Do not redistribute raw data unless provider and exchange rights explicitly permit it.
7. Backtests must use point-in-time data and publication/availability timestamps to prevent look-ahead bias.

## Recommended zero-cost stack

### Indian equities, EOD and history

**TejHQ / tej-bazaar**
- Repo: https://github.com/tejhq/tej-bazaar
- API: https://api.tejhq.dev/docs
- Dataset: https://huggingface.co/datasets/tejhq/indian-markets
- Keyless endpoints: NSE/BSE OHLCV, date snapshots, corporate actions and status.
- Free-key endpoints: adjusted prices, delivery data and symbol history; documented quota 1,000 calls/day and 300/min.
- Project coverage: NSE from 2010-01-04; BSE from 2024-07-08. Daily EOD, usually ready next morning IST.
- Terms: private individual research/backtesting/private dashboards are allowed; raw-data publishing/resale is not. TejHQ does not grant NSE/BSE exchange display or redistribution rights. Check exchange terms independently.
- Recommended use: primary daily Indian equity history and snapshot source. Validate live provider status and latest trading date before assuming data is current.

**jugaad-data**
- Repo: https://github.com/jugaad-py/jugaad-data
- Supports NSE equities, indices, F&O and RBI current rates, plus AMFI NAV according to project documentation.
- Includes caching to reduce unnecessary NSE requests.
- Recommended use: independent fallback/backfill and reconciliation source, not an uncontrolled second scraper. Pin versions, contract-test it, cache aggressively and expect exchange website changes to break extractors.

**Official exchange publications**
- NSE and BSE bhavcopy/reference/corporate-action files are canonical reconciliation targets.
- Public availability does not imply permission to republish or display. NSE's EOD tariff distinguishes internal use from website/app display; BSE publishes information-product tariffs.
- Recommended use: source validation and permitted personal research. Obtain written permission/licence before external display or distribution.

### Global prices and supplementary research

**yfinance**
- Repo: https://github.com/ranaroussi/yfinance
- Useful for supplementary global equities, ETFs, FX, indices history, company metadata and prototyping.
- Unofficial Yahoo Finance client; maintainers state Yahoo data access is intended for personal use.
- Recommended use: optional personal-research fallback only. Not canonical Indian data; cache, reconcile and tolerate schema changes.

**Alpha Vantage**
- Docs: https://www.alphavantage.co/documentation/
- Free API key. Provider support page states 25 requests/day for most users; some educational/open-source projects may qualify for expanded quotas after verification.
- Free access varies by endpoint. Do not assume free intraday, adjusted daily, bulk quote or every fundamentals endpoint.
- Recommended use: low-frequency international/macro enrichment, not broad NSE symbol polling.

### Macro, rates, policy and structural context

**RBI Database on Indian Economy (DBIE)**
- Official portal: https://dbie.rbi.org.in/
- Use RBI-published policy rates, liquidity, money/credit, forex reserves, balance of payments and other macro series where downloadable.
- Prefer official downloadable tables and metadata; build a scheduled importer with explicit frequency/release dates. Do not assume an undocumented public API exists.

**World Bank Indicators API**
- Docs: https://datahelpdesk.worldbank.org/knowledgebase/articles/889392-about-the-indicators-api
- API v2 requires no key.
- Useful for India/global GDP, inflation, trade, population and energy context. Low-frequency macro, not prices.
- Recommended use: scheduled monthly/quarterly enrichment; store indicator code, country, observation period and source.

**FRED / ALFRED**
- Docs: https://fred.stlouisfed.org/docs/api/fred/
- Free registered API key required.
- Useful for US Treasury yields, policy rates, inflation, dollar/liquidity and historical data revisions.
- Recommended use: macro risk-regime panel. Use ALFRED vintages for point-in-time backtests; keep the key server-side.

**AMFI NAV**
- Official portal: https://www.amfiindia.com/
- Useful for mutual-fund NAV time series and category context, not equity quotes.
- Recommended use: optional mutual-fund research dataset with NAV date and publication timestamp.

### News, geopolitical and event intelligence

**GDELT 2.0**
- Docs: https://www.gdeltproject.org/
- DOC API: https://api.gdeltproject.org/api/v2/doc/doc
- Free, no API key; use a conservative one-request-per-five-seconds cadence for DOC search.
- Global news/events, multilingual coverage, country/topic/company attention and tone signals.
- Recommended use: scheduled topic/entity searches for India, energy, shipping, sanctions, wars, supply chains, central banks and listed companies. Store article URL, publisher, published/seen time, query, extracted entities, confidence and deduplication key.
- Never treat article tone as truth or a direct trading signal. Require corroboration and link the original article.

## Processing tools (not data authorities)
- DuckDB: embedded analytical SQL over Parquet; strong for local EOD research and backtests.
- Polars / PyArrow: typed columnar ETL and Parquet.
- OpenBB: open-source research/workflow framework and provider integration patterns. Each provider still has its own credentials, quotas and licences; OpenBB does not itself grant free licensed Indian exchange data.
- vectorbt / backtesting.py: strategy research; enforce point-in-time data and transaction-cost assumptions.
- TA-Lib / pandas-ta: indicator computation, not independent data sources.

## RovexQuant integration order
1. Stabilize TejHQ EOD: provider health/status, latest trading date, NSE/BSE snapshot row counts, date completeness, series handling, corporate actions and stale-data UI.
2. Add historical Parquet backfill: fetch only needed partitions; ingest into the normalized PostgreSQL store; avoid one API request per symbol when bulk Parquet exists.
3. Add independent reconciliation: jugaad-data or official exchange files compare selected symbols/dates. Quarantine mismatches; never silently overwrite a good record.
4. Add macro series: World Bank + RBI DBIE + FRED. Preserve frequency and release timestamps; never join monthly observations to daily bars as if they were daily facts.
5. Add event intelligence: GDELT first, then official RBI/SEBI/PIB/company exchange announcements. Resolve entities and attach evidence before sector/stock mapping.
6. Add research computation: DuckDB/Polars, corporate-action-aware returns, point-in-time universes, sector classification history, liquidity/turnover, volatility, drawdown and event studies.
7. Expose provenance in UI: source, as-of timestamp, delay/frequency and freshness on each card. Missing data shows a reason, never a fabricated zero.

## Provider adapter contract
Each adapter should implement health(), fetch_snapshot(exchange, as_of), fetch_history(instrument, start, end), fetch_reference_data(), fetch_corporate_actions(start, end) where supported, rate_limit_policy(), source_terms_url() and data_delay_class().

All outputs pass schema validation, symbol/ISIN resolution, timezone normalization, duplicate detection, OHLC invariants, corporate-action checks and provenance validation before persistence.

## Explicit non-goals
- No scraping broker-authenticated pages or bypassing CAPTCHAs/access controls.
- No hard dependency on fragile free quotas or undocumented endpoints.
- No current-constituent-only universe in historical backtests.
- No look-ahead joins from revised macro data or late-published news.
- No live badge for EOD or stale WebSocket ticks.
- No public redistribution until display/redistribution rights are confirmed.

## Primary references checked
- TejHQ API docs: https://tejhq.dev/docs
- TejHQ data terms: https://tejhq.dev/data-terms
- tej-bazaar: https://github.com/tejhq/tej-bazaar
- jugaad-data: https://github.com/jugaad-py/jugaad-data
- yfinance: https://github.com/ranaroussi/yfinance
- World Bank API: https://datahelpdesk.worldbank.org/knowledgebase/articles/889392-about-the-indicators-api
- FRED API: https://fred.stlouisfed.org/docs/api/fred/
- Hugging Face Parquet/streaming: https://huggingface.co/docs/datasets/loading
- NSE EOD tariff: https://nsearchives.nseindia.com/web/sites/default/files/inline-files/Download%20EOD%20data%20tariff.pdf
- BSE information-products tariff: https://www.bseindia.com/downloads1/Information_Products_Pricing_Sheet.pdf