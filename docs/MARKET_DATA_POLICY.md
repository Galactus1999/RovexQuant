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
