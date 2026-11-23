# RovexQuant AI Intelligence Centre — implementation status

**Reviewed:** 23 November 2026  
**Base integration PR:** [#23 — Import governed DalalOS filing and sector research tools](https://github.com/Galactus1999/RovexQuant/pull/23)  
**Current work branch:** `feat/ai-research-history-ledger` (stacked on PR #23)

This is an evidence-based status ledger. A visible panel, stored credential, or open pull request is not proof of a live provider connection or a green build.

## Current implementation

| Capability | Status | What is actually present |
|---|---|---|
| Provider management | Implemented in existing AI Studio | Provider discovery, encrypted server-side credential storage, test connection, model selection, remove credential, provider fallback selection. |
| Dynamic NSE research tools | Existing implementation retained | Live MCP tool discovery, remote schema validation, strict read-only allowlist, authenticated on-demand calls. |
| Governed tool registry | Extended in PR #23 | Imports DalalOS capability manifest and server descriptor; per-tool enable/disable controls; read-only versus processing/quota classification; explicit per-call confirmation for processing tools; watchlist mutation disabled. |
| Multi-model orchestration | Partial / implemented foundation | Task types are exposed in AI Workspace; server-owned JSON task profiles route chat, research, macro analysis, filing extraction, claim verification and summarisation to configured provider/model/fallback chains. Existing provider gateway and credential vault remain authoritative. Requires deployment configuration and provider smoke tests. |
| Macro intelligence | Implemented through existing adapters | World Bank indicators, optional FRED series and AMFI NAV are retrieved through the existing authenticated backend. AMFI NAV is not equity pricing. |
| Event intelligence | Implemented foundation | On-demand GDELT discovery, deterministic event classification, source URLs, event categories and first-order sector transmission hypotheses. GDELT is discovery, not primary-source verification. |
| EOD event study | Implemented through existing data path | Uses persisted EOD bars and deterministic event-study calculations; displays pre/post returns, benchmark adjustment, abnormal return and limitations. It is descriptive, not causal. |
| Company filings and sector research | Optional connector added | DalalOS MCP tools expose company reports, financials, peers, shareholding, sector/macro exposure and filing extraction. It requires the owner's API key and remains disabled until configured. |
| Evidence-grounded research | Partial | Existing AI Research Tools and Briefing panels assemble selected evidence. Citation audit checks that returned reference IDs/URLs occur in the supplied evidence. This is a reference-presence check, not a semantic truth verifier. |
| Claim-level verification | Partial / pending | Source references and filing IDs are supported. Independent contradiction checking, period/entity/numeric validation and claim-level entailment are not yet complete. |
| Research history / reproducibility | Implemented foundation | New ai_research_runs migration stores run ID, task, provider/model, request hash, bounded request/response payloads, source refs, token usage, status and fallback state. Authenticated list/detail APIs and an Android history panel are included. Replay with immutable tool/data/template versions is still pending. |
| Token usage / cost ledger | Partial | Prompt/completion/total token counts are persisted per run. USD cost is recorded only when the upstream provider explicitly reports usage.cost; unknown pricing remains NOT_TRACKED, never zero. A complete pricing catalogue and USD spend aggregation remain pending. |
| Hard budget controls | Token budgets implemented; USD budgets pending | PostgreSQL advisory-lock reservations enforce optional daily and monthly token ceilings before inference. Set QUANT_AI_DAILY_TOKEN_BUDGET and/or QUANT_AI_MONTHLY_TOKEN_BUDGET; requests fail closed when a configured budget cannot be checked. USD cost caps and per-provider quotas remain pending. |
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
2. Validate task-specific model routes on the deployed backend. Configure QUANT_AI_TASK_ROUTES with task keys and provider_id/model/fallback_providers entries; provider and model IDs must exist in the current provider catalogue.
3. Complete immutable run provenance: prompt/template version, tool names/versions, source URLs/IDs, source timestamps, data cut-off and output hash; add replay with safe input snapshots.
4. Add a pinned, imported model-pricing source and USD spend aggregation. Unknown pricing must remain unknown; do not fabricate zero cost.
5. Extend the current daily/monthly token admission limits to per-task/per-provider and USD budgets; keep reservations transactional and fail closed.
6. Add claim-level verification for entity identity, period alignment, numeric consistency, source support, contradictory evidence and stale data. The verifier must use a separately configured specialist route and retain its evidence/reasons.
7. Connect event → macro → sector → company → instrument analysis only when each edge has explicit evidence, effective dates and uncertainty. Do not infer stock recommendations from ticker-name or sector-label similarity.

No phase is complete merely because its UI exists; each gate needs tests and actual runtime evidence.
