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

The optional free key is created at https://tejhq.dev/keys and is configured only as backend environment variable `TEJHQ_API_KEY`. It is not required for keyless OHLCV, snapshots or corporate actions. The free-key tier currently documents 1,000 calls/day and 300 calls/minute. Do not commit the key or put it in Android.

Quant API routes (all require the existing short-lived Quant app Bearer session):

- `GET /api/v1/app/research/market-data/providers`
- `GET /api/v1/app/research/market-data/ohlcv/{exchange}/{symbol}?from=YYYY-MM-DD&to=YYYY-MM-DD[&adjusted=true]`
- `GET /api/v1/app/research/market-data/delivery/{exchange}/{symbol}?from=YYYY-MM-DD&to=YYYY-MM-DD`
- `GET /api/v1/app/research/market-data/snapshot/{exchange}?trading_date=YYYY-MM-DD`
- `GET /api/v1/app/research/market-data/actions/{symbol}`
- `GET /api/v1/app/research/market-data/status`

Exchange is NSE or BSE. The backend validates symbol syntax, date ordering/range, row count, dates, finite positive OHLC values, OHLC consistency and non-negative integral volume/trade counts. It distinguishes provider/rate-limit failures and never returns configured API keys. The endpoints do not persist bars yet; durable local research storage and scanner integration are separate follow-on work.

### Data rights

TejHQ's data terms (updated 2026-11-08) say its free data is for one individual's personal use, including private research, backtesting, paper trading and private dashboards. They classify use inside a product, for clients or in a business as commercial use; raw data may not be redistributed on any plan. The MIT licence applies to pipeline code, not exchange data. TejHQ states it has no NSE/BSE data licence on the user's behalf. Review current terms and exchange policies before any multi-user/public/commercial use:
- https://tejhq.dev/data-terms
- https://tejhq.dev/docs

## 2. NSE MCP (manual research assistant use)

NSE's official MCP endpoints currently documented are:

- Bhavcopy: https://mcp.nseindia.in/bhavcopy/cm/mcp
- CM market: https://mcp.nseindia.in/cmmkt/mcp

NSE describes the CM market data as approximately 1–3 minutes delayed, not tick-by-tick or order-book data. The Bhavcopy service offers up to five years of EOD history. NSE's MCP terms say informational/educational use only and exclude commercial exploitation, commercial deployment, and using the data to train/fine-tune AI systems.

For that reason, Quant does not silently ingest NSE MCP into its backend or label it as a licensed live feed. Use it manually in a compatible research assistant for informational questions, subject to NSE's current terms:
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

Do not mix adjusted and unadjusted OHLC series in one backtest. Delivery history is a separate dataset and must be joined by exchange, symbol/ISIN and trading date. A missing provider timestamp is never evidence of a live quote. NSE MCP is not a substitute for a licensed live trading feed.

## References

- TejHQ API docs: https://tejhq.dev/docs
- TejHQ data terms: https://tejhq.dev/data-terms
- TejHQ data pipeline/schema: https://github.com/tejhq/tej-bazaar
- NSE MCP terms and capability description: https://www.nseindia.com/nse-mcp
- NSE reports: https://www.nseindia.in/all-reports
- NSE data policy: https://www.nseindia.com/static/market-data/nse-data-policy
