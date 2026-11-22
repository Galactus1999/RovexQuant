# Market-data permissions and provenance

RovexQuant must not scrape NSE web pages, reverse-engineer private endpoints, or treat public visibility as a data licence.

Before enabling any provider adapter, record provider/exchange, data product and delay, display/non-display permissions, permitted retention and derived-data use, rate limits, connection/session requirements, and evidence of the applicable agreement or entitlement.

NSE's non-display policy defines automated calculations or algorithms supporting trading decisions, and derived values used for internal analysis, as non-display usage. Do not assume an individual/personal-use exemption. Obtain written clarification/permission from NSE Data & Analytics where applicable.

For zero-budget local research, use user-supplied datasets only when their licence permits the intended analysis. Store provenance and do not label a CSV or delayed feed as live. No live scanner should run unless the adapter can prove its current entitlement and data freshness.

References:
- NSE Data Sharing & Usage Policy: https://www.nseindia.com/static/market-data/nse-data-policy
- NSE Non-Display Usage Policy: https://nsearchives.nseindia.com/web/sites/default/files/inline-files/Non_Display_Policy.pdf
- SEBI retail algorithmic trading circular (4 February 2025): https://www.sebi.gov.in/legal/circulars/feb-2025/safer-participation-of-retail-investors-in-algorithmic-trading_91614.html

This is an engineering control, not legal advice. Recheck official circulars and provider agreements before release.

## TejHQ personal EOD research adapter

The TejHQ adapter is restricted by the provider's published terms to one individual's personal research. Do not expose its raw responses or derived data through a public/multi-user Quant product or use it inside a business without the required TejHQ plan and applicable exchange rights. Keyless EOD OHLCV/snapshot/actions have a documented 120 requests/minute per-IP limit; the free key has 1,000 requests/day and 300/minute. Store `TEJHQ_API_KEY` server-side only. The adjusted endpoint returns raw OHLC plus `adj_close` and `adj_factor_cumulative`; do not mistake raw OHLC for adjusted OHLC. Adjusted open/high/low are calculated by multiplying raw values by the factor; volume remains as traded. Re-fetch adjusted history after corporate-action ex dates.

NSE MCP is for informational/educational use under NSE's published terms. Do not wire its feed into a public Quant product, use it for commercial deployment, or use it for AI training/fine-tuning. The approximate 1–3-minute delayed stream is not tick data and is not a licensed trading feed.


## NSE MCP educational-research exception and boundary

The official NSE MCP endpoints are integrated only as a manually invoked, authenticated, single-owner educational/research source. This is not permission to use the data in unattended scanners, automated trading-decision algorithms, broker execution, public/multi-user displays, or AI/ML training. The app must label Bhavcopy as `END_OF_DAY` and CM market data as `DELAYED`, preserve any source timestamp returned by NSE, and never substitute backend retrieval time for an exchange timestamp. Raw MCP responses are not persisted by the connector. The connector discovers tools dynamically and blocks tool names suggesting mutation/trading and tools explicitly annotated as not read-only.

The general non-display policy warning above remains applicable to any intended non-display or automated decision use. The private MCP connector does not override that policy or grant a blanket licence. Obtain NSE's written clarification/permission before expanding into background scanning, automated strategy decisions, data retention, sharing, or commercial/multi-user use.
