# RovexQuant AI Intelligence Centre — implementation status

**Reviewed:** 23 November 2026  
**Integration PR:** [#23 — Import governed DalalOS filing and sector research tools](https://github.com/Galactus1999/RovexQuant/pull/23)  
**Branch:** `feat/ai-intelligence-tool-registry`

This is an evidence-based status ledger. A visible panel, stored credential, or open pull request is not proof of a live provider connection or a green build.

## Current implementation

| Capability | Status | What is actually present |
|---|---|---|
| Provider management | Implemented in existing AI Studio | Provider discovery, encrypted server-side credential storage, test connection, model selection, remove credential, provider fallback selection. |
| Dynamic NSE research tools | Existing implementation retained | Live MCP tool discovery, remote schema validation, strict read-only allowlist, authenticated on-demand calls. |
| Governed tool registry | Extended in PR #23 | Imports DalalOS capability manifest and server descriptor; per-tool enable/disable controls; read-only versus processing/quota classification; explicit per-call confirmation for processing tools; watchlist mutation disabled. |
| Multi-model orchestration | Partial | Existing provider gateway and transient-failure fallback are present. Task-specific specialist routing and independent model selection per task are not yet implemented. |
| Macro intelligence | Implemented through existing adapters | World Bank indicators, optional FRED series and AMFI NAV are retrieved through the existing authenticated backend. AMFI NAV is not equity pricing. |
| Event intelligence | Implemented foundation | On-demand GDELT discovery, deterministic event classification, source URLs, event categories and first-order sector transmission hypotheses. GDELT is discovery, not primary-source verification. |
| EOD event study | Implemented through existing data path | Uses persisted EOD bars and deterministic event-study calculations; displays pre/post returns, benchmark adjustment, abnormal return and limitations. It is descriptive, not causal. |
| Company filings and sector research | Optional connector added | DalalOS MCP tools expose company reports, financials, peers, shareholding, sector/macro exposure and filing extraction. It requires the owner's API key and remains disabled until configured. |
| Evidence-grounded research | Partial | Existing AI Research Tools and Briefing panels assemble selected evidence. Citation audit checks that returned reference IDs/URLs occur in the supplied evidence. This is a reference-presence check, not a semantic truth verifier. |
| Claim-level verification | Partial / pending | Source references and filing IDs are supported. Independent contradiction checking, period/entity/numeric validation and claim-level entailment are not yet complete. |
| Research history / reproducibility | Not implemented | No durable backend research-run ledger or replay workflow with source/tool/model/prompt/data fingerprints. |
| Token usage / cost ledger | Not implemented | No persisted per-user/per-task token and USD ledger. Provider response success must not be interpreted as cost tracking. |
| Hard budget controls | Not implemented | No server-side spend caps, preflight admission, per-provider quotas or budget-stop mechanism yet. |
| Data permissions / audit | Partial | Connector key is encrypted server-side; tools are manually enabled; processing tools require confirmation; destructive watchlist tools are blocked. Persistent permission history and audit export remain pending. |

## Source and architecture reuse

- Existing provider gateway and the current authenticated API remain the owners.
- Existing macro, GDELT/event, EOD persistence, event-study and NSE MCP services are reused.
- Existing AI Research Tools and Briefing components from the newer AI branch are imported rather than reimplemented.
- DalalOS's MIT-licensed capability manifest and server descriptor are imported at pinned upstream revision `f0a4a39614b1a9ac05a181bc34401a8e6d387976`. Its runtime package is not vendored or executed.
- No separate market-data ingestion pipeline or second backend owner is introduced.

## Provider and data-use qualifications

- DalalOS is an optional external research service with its own free-tier credits, rate limits and concurrency limits. Live authentication cannot be verified until the owner supplies an API key.
- DalalOS's terms prohibit quota bypass and large-scale raw-data redistribution; underlying exchange data remains subject to exchange rights. This connector is for the owner's private research with attribution.
- NSE MCP remains a separate research connector. It is not promoted to a live quote provider or an automated scanner.
- TejHQ EOD remains the authoritative persisted historical price path. No EOD or delayed source is labelled live.
- All external MCP tool outputs are untrusted data. The model must not follow embedded instructions from a filing or source response.

## CI and release status

PR #23 is a draft. The exact-head GitHub Actions run is the acceptance authority. Do not mark this implementation green until Android compile/lint/APK integrity, backend regression, migration/vault tests, secret scan, security/API contract checks and emulator navigation all pass on the final commit.

A successful source commit or an individual passing job is not a green release. Live DalalOS authentication and plan quota remain unverified until the owner configures a key and performs an on-demand connection test.

## Next implementation gates

1. Finish exact-head CI and fix every failure.
2. Complete task-specific model routing inside the existing provider gateway; keep the current credential vault as the only secret owner.
3. Add a durable research-run ledger using the existing database and migration system. Record stable run ID, prompt/template version, model/provider, tool names/versions, source URLs/IDs, source timestamps, data cut-off and output hash.
4. Add token/cost accounting using provider-reported usage and a pinned, imported model-pricing source. Unknown pricing must remain unknown; do not fabricate zero cost.
5. Enforce server-side per-run and rolling budgets before model/tool calls; stop cleanly at limits and show the reason.
6. Add claim-level verification for entity identity, period alignment, numeric consistency, source support, contradictory evidence and stale data.
7. Connect event → macro → sector → company → instrument analysis only when each edge has explicit evidence, effective dates and uncertainty. Do not infer stock recommendations from ticker-name or sector-label similarity.

No phase is complete merely because its UI exists; each gate needs tests and actual runtime evidence.
