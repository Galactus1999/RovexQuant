# Quant research data sources (initial zero-cost phase)

## Scope and operating mode

This integration is for the owner's private research, backtesting and paper research. It does not create a public data redistribution service, broker connection or order-placement feature. Every TejHQ price response is explicitly labelled `END_OF_DAY`; do not present it as intraday, delayed-live or real-time data.

## 1. TejHQ EOD API

Base URL: https://api.tejhq.dev

Documented endpoints used by Quant:

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `GET /v1/ohlcv/{exchange}/{symbol}?from=YYYY-MM-DD&to=YYYY-MM-DD` | Keyless | Daily OHLCV history |
| `GET /v1/snapshot/{exchange}?date=YYYY-MM-DD` | Keyless | Full-market EOD snapshot |
| `GET /v1/actions/{symbol}` | Keyless | Corporate actions |
| `GET /v1/adjusted/{exchange}/{symbol}?from=...&to=...` | Optional free key | Split/dividend-adjusted daily prices |
| `GET /v1/delivery/{exchange}/{symbol}?from=...&to=...` | Optional free key | Delivery quantity and percentage |
| `GET /v1/status` | Keyless | Provider freshness/status |

The optional free key is created at https://tejhq.dev/keys and is configured only as backend environment variable `TEJHQ_API_KEY`. It is not required for keyless OHLCV, snapshots or corporate actions. Keyless requests are limited to 120/minute per IP; the free-key tier currently documents 1,000 calls/day and 300 calls/minute. Do not commit the key or put it in Android.

Quant API routes (all require the existing short-lived Quant app Bearer session):

- `GET /api/v1/app/research/market-data/providers`
- `GET /api/v1/app/research/market-data/ohlcv/{exchange}/{symbol}?from=YYYY-MM-DD&to=YYYY-MM-DD[&adjusted=true]`
- `GET /api/v1/app/research/market-data/delivery/{exchange}/{symbol}?from=YYYY-MM-DD&to=YYYY-MM-DD`
- `GET /api/v1/app/research/market-data/snapshot/{exchange}?trading_date=YYYY-MM-DD`
- `GET /api/v1/app/research/market-data/actions/{symbol}`
- `GET /api/v1/app/research/market-data/status`

Exchange is NSE or BSE. The backend validates symbol syntax, date ordering/range, row count, dates, finite positive OHLC values, OHLC consistency and non-negative integral volume/trade counts. It distinguishes provider/rate-limit failures and never returns configured API keys. For adjusted responses, the adapter preserves raw OHLC, adjusted close and the cumulative adjustment factor. Adjusted open/high/low must be calculated by multiplying raw OHLC by that factor; volume remains raw. Do not use raw close across a split/bonus for returns or backtests. The endpoints do not persist bars yet; durable local research storage and scanner integration are separate follow-on work.

### Data rights

TejHQ's data terms (updated 2026-11-08) say its free data is for one individual's personal use, including private research, backtesting, paper trading and private dashboards. They classify use inside a product, for clients or in a business as commercial use; raw data may not be redistributed on any plan. The MIT licence applies to pipeline code, not exchange data. TejHQ states it has no NSE/BSE data licence on the user's behalf. Review current terms and exchange policies before any multi-user/public/commercial use:
- https://tejhq.dev/data-terms
- https://tejhq.dev/docs

## 2. NSE MCP (private educational/research use)

NSE's official MCP endpoints currently documented are:

- Bhavcopy: https://mcp.nseindia.in/bhavcopy/cm/mcp
- CM market: https://mcp.nseindia.in/cmmkt/mcp

NSE describes the CM market data as approximately 1–3 minutes delayed, not tick-by-tick or order-book data. The Bhavcopy service offers up to five years of EOD history. NSE's MCP terms say informational/educational use only and exclude commercial exploitation, commercial deployment, and using the data to train/fine-tune AI systems.

For this repository's current single-user, non-commercial educational/research use, Quant may integrate the official NSE MCP servers as an on-demand research source, subject to NSE's current terms. The integration must:
- make requests only for the authenticated owner's research session; do not expose an unauthenticated public data proxy or multi-user data service;
- use data only to answer informational/educational questions and perform transparent, on-demand descriptive calculations;
- never use NSE MCP data to train, fine-tune, evaluate or train, fine-tune, evaluate or improve any AI/ML model or algorithm;
- never redistribute raw NSE data, offer a public data feed, place/route orders, or represent the delayed stream as exchange-authorized live trading data;
- label CM market responses as `DELAYED` with the provider's observed timestamp/freshness where supplied, and Bhavcopy as `END_OF_DAY`;
- avoid building a persistent raw-data archive from MCP responses unless NSE confirms the permitted retention terms in writing.

Quant implements this as an authenticated, on-demand research connector. The backend performs MCP initialize, tools/list and tools/call over the two official Streamable HTTP endpoints. Tool names and JSON input schemas are discovered from NSE at request time; Quant does not assume tool names or scrape the NSE website. Tools with write/trade/order-like names or an explicit `readOnlyHint: false` are blocked. The live NSE catalogues currently omit `readOnlyHint` on their data tools; in that case Quant permits only exact locally reviewed tool names whose live descriptions are data-query operations. Unknown names, unsafe descriptions and explicit negative hints remain denied. This is not a blanket `get_*` rule. The tool catalogue is not treated as a market-data entitlement, and remote tool results are not cached or persisted by these routes. See NSE's current MCP capabilities and disclaimer: https://www.nseindia.com/nse-mcp.

Android integration: the signed-in Research Lab calls these routes using the existing in-memory Quant app session. It discovers both server catalogues, shows only tools permitted by the backend read-only guard, displays the current tool's input JSON schema, and submits a user-authored JSON argument object. Results are labelled by source mode and show a source timestamp only when NSE explicitly supplies one. This is an interactive research console, not an unattended scanner or order workflow.

Authenticated app routes:
- `GET /api/v1/app/research/nse-mcp/providers`
- `GET /api/v1/app/research/nse-mcp/tools?server=bhavcopy|cmmkt|both`
- `POST /api/v1/app/research/nse-mcp/call` with `{"server":"bhavcopy|cmmkt","tool":"<discovered-tool-name>","arguments":{...}}`

The app labels Bhavcopy calls `END_OF_DAY` and CM market calls `DELAYED`. Retrieval time is not represented as the exchange's source timestamp; use only timestamps present in the NSE tool response. This is not an automated live scanner, trading signal engine, order router or AI training source. A manually requested result can be inspected and used for transparent educational calculations, but do not silently feed this data into background strategy execution or automated trading-decision pipelines.

This is a private non-commercial research configuration, not a blanket licence or legal determination. Recheck the official terms before changing deployment scope, storing/redistributing data, adding users, monetizing it or using it for any commercial purpose:
- https://www.nseindia.com/nse-mcp

## 3. Official NSE/BSE reports

Use official exchange report portals for source verification and manually downloaded exchange files:

- NSE reports: https://www.nseindia.in/all-reports
- NSE market-data policy: https://www.nseindia.com/static/market-data/nse-data-policy
- BSE: https://www.bseindia.com/

NSE's current report portal lists the CM-UDiFF Common Bhavcopy Final (ZIP), Full Bhavcopy and Security Deliverable data, security master files, price bands, corporate/surveillance reports and historical reports. The older CM bhavcopy CSV formats are marked discontinued from 2024-07-08; use the current UDiFF report format when validating new data.

Quant's existing generic OHLCV CSV importer requires columns `timestamp,open,high,low,close,volume` with timezone-aware timestamps. It is not yet a dedicated parser for raw NSE UDiFF/BSE exchange report layouts. Do not rename columns blindly or assume two exchange formats are interchangeable. A dedicated, fixture-tested official Bhavcopy parser is a separate next step.

## 4. Data provenance and scanner guardrails

For all research results retain:
- provider/source identifier and exchange;
- trading date / exchange timestamp where the source supplies one;
- retrieval timestamp in UTC;
- data mode (`END_OF_DAY`, `DELAYED`, `LIVE` only when an entitled feed proves it);
- adjusted/unadjusted status and corporate-action provenance;
- missing rows, gaps, validation failures and stale-data status.

Do not mix adjusted and unadjusted OHLC series in one backtest. Delivery history is a separate dataset and must be joined by exchange, symbol/ISIN and trading date. A missing provider timestamp is never evidence of a live quote. NSE MCP is not a substitute for a licensed live trading feed. Do not use NSE MCP data for AI/ML training, fine-tuning, model evaluation or algorithm training.

## References

- TejHQ API docs: https://tejhq.dev/docs
- TejHQ data terms: https://tejhq.dev/data-terms
- TejHQ data pipeline/schema: https://github.com/tejhq/tej-bazaar
- NSE MCP terms and capability description: https://www.nseindia.com/nse-mcp
- NSE reports: https://www.nseindia.in/all-reports
- NSE data policy: https://www.nseindia.com/static/market-data/nse-data-policy


## TejHQ EOD

- Official API documentation: https://tejhq.dev/docs/api/market-data
- Terms: https://tejhq.dev/terms
- The keyless full-market NSE/BSE snapshot is an end-of-day source. TejHQ documents a trading-day 18:30 IST run with a 21:30 IST retry; its current data terms say data is usually ready by the next morning IST and provide no SLA. Treat the `/v1/status` response, not the cron target, as the freshness authority; it is not an intraday feed.
- Current terms permit personal research, private dashboards and local storage, but prohibit redistribution and commercial use. Quant is restricted to the private owner installation unless the provider grants broader rights.
- The authenticated app endpoint `/api/v1/app/market-data/eod/latest/{exchange}` searches backwards for the latest non-empty trading date (bounded lookback), then returns a deterministic summary: EQ-series coverage, advances/declines/unchanged, top gainers/losers and most-active stocks.
- Android requests this snapshot automatically after successful login and exposes manual refresh. The UI labels it `END_OF_DAY` and displays the actual trading date; retrieval time is never presented as a market timestamp.
- This endpoint does not persist raw snapshots or claim continuous scheduled collection. A durable daily ingestion worker and historical snapshot store remain separate roadmap work.
- Upstox V3 remains the optional live WebSocket provider. A stored token is not proof of a live feed; the app must receive valid ticks before reporting live connectivity.


## 5. Additional free primary and discovery sources (research reviewed 2026-11-22)

These sources are candidates for the next ingestion phase. A source being free to query does not automatically grant rights to redistribute its content. Store source metadata, canonical URLs, retrieval times and only the content fields permitted by each source.

### Indian listed-company disclosures

- Official NSE RSS: https://www.nseindia.in/static/rss-feed
- Feed categories include announcements, annual reports, board meetings, financial results, corporate actions, insider trading, shareholding patterns, circulars and other issuer disclosures.
- Use RSS as a change-discovery mechanism. Fetch and verify the linked exchange filing before creating a factual event or company-impact claim. Preserve the original filing URL and publication metadata.
- Do not scrape undocumented NSE JSON endpoints. NSE's market-data policy requires subscriber agreements for licensed market data and restricts redistribution. Official real-time data products are subscription/licensing products.
- NSE Indices publishes historical index-data reports and sector-index factsheets. Use the official historical index-data download for benchmark time series where the data terms permit. Index constituent/weight data is a separate product and may require a subscription; do not infer full constituent weights from a factsheet or from a sector label.

### Live Indian market prices

- Upstox Developer API: https://upstox.com/developer/api-documentation/
- Upstox advertises its trading and market-data APIs as free to use; the account owner must create an app and authorize access. Quant's Upstox V3 stream remains optional and must display LIVE only after a successful authorized connection and valid source ticks.
- Keep the user's token server-side in the encrypted provider vault. Do not store it in Android preferences or commit it to Git.
- Dhan Data API is currently a paid subscription; it is not a zero-cost fallback. Do not design the free tier around it.
- Alpha Vantage's free service currently documents 25 requests/day. It is not suitable as the primary full-universe Indian scanner feed.
- Do not build production collection on Yahoo Finance's unofficial yfinance endpoints; Yahoo does not publish an official public API and its terms restrict automated collection without permission.

### Global news, geopolitical events and policy discovery

- GDELT Project: https://www.gdeltproject.org/ and DOC API documentation at https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/
- GDELT is free/open for research and other uses under its published terms and is useful for broad multilingual discovery. It is not a primary-source verification service. Apply conservative request pacing, caching, deduplication and bounded queries; rate limits may be imposed.
- Use GDELT article URLs to find source material, then prefer official government, regulator, company or exchange sources for factual verification.
- Add primary-source RSS feeds from RBI, SEBI, Ministry of Finance, PIB, DGFT, US Treasury, Federal Reserve, ECB and other relevant authorities only after checking their feed terms and stable URLs.

### Macro and cross-country indicators

- World Bank Indicators API: https://datahelpdesk.worldbank.org/knowledgebase/articles/889392-about-the-indicators-api
  - No API key is required; it exposes thousands of economic and development time series. Best for lower-frequency structural and cross-country context, not intraday market signals.
- FRED/ALFRED: https://fred.stlouisfed.org/docs/api/fred/
  - Free personal account and API key; useful for US rates, yields, inflation, credit conditions, dollar and global macro series. Respect attribution and the series-level third-party rights; retain release/vintage timestamps to avoid look-ahead bias.
- RBI DBIE: https://dbie.rbi.org.in/
  - Official RBI statistical portal with a large catalogue of Indian macro/financial series and downloadable outputs. Use documented downloads or APIs only; do not assume an undocumented endpoint is a supported public API.
- IMF Data API: https://data.imf.org/en/Resource-Pages/IMF-API
  - SDMX APIs expose IMF datasets. Confirm the current account/access and dataset-specific conditions before automating collection.

### Company filings and financial statements outside India

- SEC EDGAR APIs: https://www.sec.gov/search-filings/edgar-application-programming-interfaces
  - Free JSON submissions and XBRL company-facts APIs; no API key required. Updated as filings are disseminated. Send a descriptive User-Agent and stay at or below the SEC's published 10 requests/second maximum.
  - Use for US issuer filings and global macro/industry context where relevant; it is not an Indian company filing source.

### Science, AI, inventions and patents

- arXiv RSS/Atom: https://info.arxiv.org/help/rss.html
  - Free daily subject feeds (including cs.AI, cs.LG, cs.CL, quant-ph and semiconductor-adjacent categories). Treat preprints as research announcements, not peer-reviewed validation or proof of commercial adoption.
- Crossref REST API: https://www.crossref.org/documentation/retrieve-metadata/rest-api
  - Public metadata API with no registration required. Use the polite pool with an identifying contact; abstracts and publisher full text may carry separate copyright.
- OpenAlex API: https://help.openalex.org/api/authentication/
  - Basic access is free; a free API key increases the daily budget and enables deeper cursor paging. It is useful for scholarly works, institutions, topics and citation relationships, not real-time stock data.
- USPTO PatentsView: https://www.uspto.gov/ip-policy/economic-research/patentsview
  - Public patent datasets and search tools for US patenting activity. Patent publication is not proof of technical feasibility, product launch or revenue impact. Indian patent coverage requires a separate India-specific source and terms review.

## 6. Recommended zero-cost acquisition plan

1. **NSE/BSE EOD equities:** TejHQ keyless full-market snapshot and OHLCV. Fetch once after the provider's published daily update window, check /v1/status, and store the date and freshness state. Backfill from the documented Parquet dataset rather than making thousands of symbol-by-symbol calls.
2. **Intraday/live equities:** owner-authorized Upstox V3 WebSocket. Subscribe to the verified NSE equity and benchmark instrument masters, track each instrument's last valid tick, reconnect state and stale age. Do not label a credential as connected until ticks arrive.
3. **Corporate disclosures:** NSE RSS + original exchange filing links. Add BSE primary disclosures only through documented endpoints/feeds and source terms.
4. **Global events:** GDELT discovery + original publisher/government/issuer verification. Deduplicate by canonical URL/content hash and retain both detected and source-published timestamps.
5. **Macro:** World Bank (keyless) + RBI DBIE documented exports + FRED (free key) + selected IMF SDMX datasets.
6. **AI/science/patents:** arXiv + Crossref/OpenAlex + USPTO datasets; classify these as research signals and require evidence of company exposure before assigning a listed stock.
7. **Sector performance:** official NSE/Nifty sector-index historical values plus live Upstox sector-index ticks. Begin with index-level return/relative-strength rankings. Constituent-level attribution requires a verified point-in-time constituent map and weights; do not fabricate one.
8. **Scheduling:** the repository is public, so standard GitHub-hosted Actions runners are free. A daily EOD workflow is a practical zero-hosting-cost scheduler once committed to the default branch and connected to durable PostgreSQL storage. GitHub schedules run only from the default branch, can be delayed, and may be disabled after 60 days without repository activity; they are not an intraday SLA. Use the persistent database for idempotent snapshots/job state, not the runner filesystem.
9. **Durability:** Render Free is not a dependable always-on worker or durable filesystem. Use PostgreSQL for market history, evidence metadata, source-health and job state. A scheduler must write idempotently, checkpoint the source trading date, retry safely and expose its last success/failure to the app.

## 7. External research references

- NSE Data Sharing & Usage Policy: https://www.nseindia.in/static/market-data/nse-data-policy
- NSE real-time data products: https://www.nse.in/static/market-data/real-time-data-subscription
- TejHQ API: https://tejhq.dev/docs
- TejHQ data terms: https://tejhq.dev/data-terms
- Upstox V3 feed: https://upstox.com/developer/api-documentation/v3/get-market-data-feed/
- GitHub Actions billing: https://docs.github.com/en/actions/concepts/billing-and-usage
- GitHub Actions schedule event: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- World Bank API: https://datahelpdesk.worldbank.org/knowledgebase/articles/889392-about-the-indicators-api
- FRED API: https://fred.stlouisfed.org/docs/api/fred/
- SEC EDGAR APIs: https://www.sec.gov/search-filings/edgar-application-programming-interfaces
- arXiv RSS: https://info.arxiv.org/help/rss.html
- Crossref REST API: https://www.crossref.org/documentation/retrieve-metadata/rest-api
- OpenAlex API: https://help.openalex.org/api/authentication/
- USPTO PatentsView: https://www.uspto.gov/ip-policy/economic-research/patentsview
