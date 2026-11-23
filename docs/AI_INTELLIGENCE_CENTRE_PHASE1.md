# AI Intelligence Centre — implementation slice

## Branch and PR
- Branch: `feat/ai-intelligence-research-workspace`
- PR: #22 (draft)
- Baseline: existing AI provider integration branch, preserving its backend gateway and credential vault work.

## Executable research panels added
The Intelligence Centre now exposes two additional on-demand workflows through the existing `QuantDataRepository` contract:

1. **Macro observations**
   - World Bank India indicators already implemented by `market_data.macro_data`.
   - FRED configuration/series availability through the existing backend endpoint.
   - AMFI NAV examples are explicitly labelled as mutual-fund NAV, not equity quotes.
   - Partial provider failures remain visible instead of suppressing successful sources.

2. **Historical EOD event study**
   - Calls the existing authenticated `/api/v1/research/event-study/{exchange}/{symbol}` route.
   - Reads the existing persisted EOD market history and deterministic event-study engine.
   - Shows pre-event return, post-event cumulative return, market-adjusted return, cumulative abnormal return, benchmark matching and method limitations.
   - Explicitly warns that event-window association is descriptive and does not establish causality.

No second market-data pipeline, provider client, persistence owner, or calculation engine was introduced.

## UI research principles applied
- Prefer analytical workspaces with source/freshness context over decorative KPI cards.
- Keep mobile workflows focused and progressively reveal detail.
- On larger windows, use supporting panes rather than stretching a phone column. Follow Android's Material 3 adaptive guidance:
  - https://developer.android.com/design/ui/mobile/guides/layout-and-content/adapt-layout
  - https://developer.android.com/develop/adaptive-apps/guides/build-a-supporting-pane-layout
- Keep numeric formats, timestamps, data mode and missingness explicit. Color must not be the only carrier of meaning.

## Acceptance status
- Source implementation committed on the branch.
- Exact-head CI run: https://github.com/Galactus1999/RovexQuant/actions/runs/45163798818
- Do not mark this slice green until the exact-head Android compile/lint/APK integrity, backend regression, secret scan and emulator/navigation checks complete.

## Not implemented by this slice
These remain explicit engineering work, not implied by the presence of the panels:
- Server-governed tool registry with per-tool enable/disable, scopes and audit.
- Task-aware model routing and bounded multi-model orchestration.
- Evidence claim linker and independent verifier.
- Durable research run history/replay, including prompt, model, source and data-version fingerprints.
- Usage ledger, token/cost estimates and hard budget admission controls.
- Evidence-backed event → macro → sector → company → instrument graph and source-attributed research reports.
- Tablet/desktop multi-pane implementation and UI automation coverage for the new panels.

## Next vertical slice
Implement the governed tool registry and task router against the existing FastAPI composition root and provider gateway. Registry entries must resolve to existing services; tools must have explicit data modes, permissions, quotas and source terms. No arbitrary model-invoked tool execution or silent access to NSE MCP is allowed. Persist research runs only after the ownership and database migration path is confirmed.
