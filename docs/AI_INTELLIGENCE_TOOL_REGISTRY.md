# AI Intelligence Centre — governed external research tool registry

## Imported upstream contracts

RovexQuant imports these exact upstream files from the public MIT-licensed DalalOS repository:

- `capabilities.json` → `backend/src/rovexquant/market_data/data/dalalos_capabilities.json`
- `server.json` → `backend/src/rovexquant/market_data/data/dalalos_server.json`
- Upstream MIT license notice → `third_party/licenses/DalalOS-MIT-LICENSE.txt`

Upstream project: https://github.com/BeyondNoww/DalalOS-Indian-Stock-Research  
MCP endpoint: https://mcp.dalalos.in/mcp  
PyPI helper: https://pypi.org/project/dalalos-mcp/

The imported repository is a capability contract and server descriptor, not a full self-hosted server implementation. RovexQuant does **not** vendor an unreviewed second market-data pipeline or execute arbitrary external code. The existing authenticated MCP client, encrypted token vault, request controls and repository/UI path are reused.

## Available research coverage

The upstream capability manifest advertises company reports, financials/trends, peer comparisons, shareholding/pledge, company and macro exposures, sector overview/flows, FII/DII, macro indicators, corporate actions, filings and freshness metadata.

The connector is complementary to the existing NSE MCP and TejHQ EOD path:
- Existing NSE MCP remains the source for its published official NSE tools.
- Existing TejHQ EOD persistence remains RovexQuant's authoritative EOD history.
- DalalOS is an optional, source-attributed external research connector. Its prices are EOD, and filing/financial datasets have their own refresh cadence.
- DalalOS filing documentation describes BSE disclosures. Do not assume NSE-only filings are covered.

## Permission policy

The imported manifest is an allowlist, not a trust assertion.

- Only non-deprecated tools present in the imported manifest are discoverable.
- Tools marked `read_only=true` can be enabled for explicit user research.
- Tools marked `read_only=false` are classified as processing/quota tools and require an explicit per-call user confirmation. Filing extraction can queue work and consume a small daily allowance.
- `add_to_watchlist` and `remove_from_watchlist` are disabled. RovexQuant does not expose external watchlist writes.
- Unknown tools, deprecated tools, destructive tools, schema references, and metadata contradictions fail closed.
- Connector API keys are stored under the existing encrypted `provider_tokens` vault namespace. They are never returned to the Android client or logged.

## Provider terms and data boundaries checked

DalalOS Terms of Service (last updated 14 November 2026): https://dalalos.in/terms

The provider states that:
- Users must obey plan rate limits, monthly credits and concurrency caps.
- Scraping or bulk automation outside normal MCP/API use and attempts to bypass quotas are prohibited.
- Reselling or redistributing raw responses at scale is prohibited.
- Underlying exchange data remains the property of the exchange/regulatory publisher; DalalOS does not claim a formal exchange redistribution license.
- It provides raw disclosed facts and mechanical ratios, not investment advice or buy/sell calls.

The stated allowance for a user's own research does not remove the need to preserve attribution or verify underlying filings. RovexQuant must not republish raw exchange data or present provider facts as verified investment recommendations.

Useful upstream references:
- Filing extraction and limits: https://dalalos.in/use-cases/read-bse-nse-company-filings-mcp
- Tool reference and schemas: https://dalalos.in/tools
- Data freshness: https://dalalos.in/status
- Data sources and freshness: https://dalalos.in/docs/data-sources-and-freshness

## Operational status

The connector is optional and disabled until the owner enters an API key from https://dalalos.in/dashboard/keys. Credential storage status is not a successful connection test. A successful tool discovery is the connection test; every returned result must retain source/freshness fields where supplied.

## Remaining AI Intelligence Centre work

- Explicit enable/disable state for individual discovered tools, persisted as user configuration.
- Task-aware model routing through the existing AI gateway.
- Claim-level evidence references linking event, macro, filing, sector and instrument observations.
- Independent verification of extracted claims and numeric periods.
- Reproducible research-run history with source/tool/model/prompt fingerprints.
- Token/cost accounting, request budgets and preflight spend controls.
- Tool-call audit trail and user-visible data-permission history.

These are separate implementation gates; this connector alone does not complete the AI Intelligence Centre.
