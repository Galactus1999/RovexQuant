# RovexQuant Recovery Status

Last reviewed: 2026-11-22

This document records the evidence-backed recovery baseline. It is not a claim that production connectivity or all product phases are complete.

## Verified repository state

- The integration branch contains the authenticated Android Intelligence Centre, optional AI chat workspace, GDELT on-demand event discovery, NSE cash-equity instrument-master loading, and the Upstox V3 WebSocket bridge.
- Upstox V3 subscription requests must be sent as WebSocket binary frames. The authorization endpoint requires `Accept: application/json`; the WebSocket stream itself carries Protobuf binary feed messages.
- The authoritative product requirements are in `docs/prompts/ROVEXQUANT_AI_INTELLIGENCE_BUILD_PROMPT.md`.
- The AI contracts and implementation boundaries are documented in `docs/AI_INTELLIGENCE_ARCHITECTURE.md` and `docs/AI_INTELLIGENCE_IMPLEMENTATION_STATUS.md`.
- The AI provider gateway is server-side. Android provider discovery and chat use the authenticated app session; provider API keys must never be shipped in the APK.
- GDELT is an on-demand discovery source. Its headline classifications and sector exposures are hypotheses, not verified company-level impacts or recommendations.
- No durable ingestion worker or scheduled job system is currently established. Do not claim continuous background collection on an ephemeral/free web service.

## Runtime prerequisites that must be checked on the actual deployment

- A valid owner-generated Upstox Analytics Token must be provisioned in encrypted backend storage before live Upstox streaming can work.
- At least one supported AI provider credential and a valid model ID must be configured server-side before AI chat can return a completion.
- The deployed backend must run the same compatible source revision as the APK.
- The deployed PostgreSQL schema, encrypted vault readiness, provider status, and backend health must be checked without exposing credentials.
- A successful app login alone does not prove market-feed authorization, subscription acknowledgement, first snapshot, ongoing ticks, AI provider readiness, or durable ingestion.

## Acceptance rules

1. Verify exact source commit and deployment revision before diagnosing the installed APK.
2. Test the complete path: provider credential -> upstream authorization -> subscription -> first market snapshot -> normalized tick -> Android rendering.
3. Test AI end to end: app session -> configured provider catalogue -> encrypted credential selection -> upstream completion -> Android rendering.
4. Keep delayed, EOD, historical and live data modes distinct, with source timestamps and quality.
5. Never fabricate prices, news, company mappings, financial metrics, or stock recommendations.
6. Keep the app research-only; no order placement or trading.
7. A phase is complete only after code, tests, documentation, exact-head CI and required deployment/device evidence pass.
