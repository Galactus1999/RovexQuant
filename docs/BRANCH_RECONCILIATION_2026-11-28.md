# RovexQuant ahead-branch reconciliation — 2026-11-28

## Baseline and method

- Repository: `Galactus1999/RovexQuant`
- Integration base: `main` at `d58173d0bc29d0aff3a9ec119b6f6bb48f11bc28` (the current base verified while auditing PR #40).
- Live branch inventory: 53 branches including `main`.
- Commit comparison against `main`: 24 non-main branches have commits ahead; 28 non-main branches have no commits ahead. A commit-ahead count is not proof that its functionality is absent from `main`.
- The audit compared file-level changes and inspected current-main source for the major feature families. No old branch was merged wholesale.

## Functionality already represented on current main

The following branch families contain substantial code that is already represented by the current implementation, often in a newer or safer form:

- AI provider discovery, server-side routing, AI research tools, claim verification, and persistent research history: `feat/ai-auto-provider-discovery`, `feat/ai-integrations-studio`, `feat/ai-intelligence-tool-registry`, `feat/ai-research-history-ledger`, `integration/ai-studio-mainline`, `integration/ai-studio-mainline-v2`, `integration/quant-ai-research-history-mainline`, `integration/quant-ai-research-mainline`.
- Instrument identity, company/index search, index/sector research and validated charts: `feature/pulse-interactive-research`, `fix/p1-instrument-identity`, `feature/p2-indicator-integration`.
- EOD persistence, leased ingestion worker, macro observations, AMFI NAV, event studies and research-data health: `fix/p1-market-data-flow`, `feat/research-data-connectors-launcher`, `fix/quant-phase1-nse-mcp-live-smoke`.
- Bounded Upstox market-stream handling: `feat/p0-bounded-market-stream`. Current main has the bounded frame queue and explicit chart-flow capacity; the older branch version is not a safe replacement.
- Backtesting and indicator engine foundations, portfolio/risk screens and related quality UI: `feat/quant-engine-quality-ui-expansion`. Current main already contains the backtesting engine, indicator implementations and portfolio workspace.
- Mainline visual workspace and market-pulse functionality: `feat/frontend-visual-first`. Its additional visual helper file is not a standalone product feature and is not referenced by the current main screen; do not add dead UI code.
- The older `QuantTerminal` prototype from `feat/premium-quant-terminal-ui` is a disconnected demo with placeholder prices/charts and explicit “awaiting feed” content. It must not replace the live, data-backed main workspace.
- `feat/research-data-connectors-launcher` contains an older `QuantWorkspaceViewModel` and `MarketPulseScreen`; current main has offline instrument-master cache fallback and more complete live-data, EOD and copilot UI.
- `feature/p2-indicator-integration` has an older chart/indicator implementation. Current main adds source-offset-aware indicator coverage, semantic chart labels/sample coverage and stricter indicator value validation.
- `fix/quant-phase1-nse-mcp-live-smoke`'s current `nse_mcp.py` is byte-for-byte the same as main in the inspected path; its older branch diff does not justify a port.

## Distinct changes retained separately

### PR #40 — universal CSV/Bhavcopy ingestion

Keep `feat/universal-market-data-acquisition` in its own draft PR rather than mixing it into unrelated UI/build changes. Compared with main, its 19 changed files add the universal parser, exchange Bhavcopy split/import, bounded ZIP/encoding support, imported-dataset auto-refresh manager, bulk EOD history endpoint, and tests.

- PR: https://github.com/Galactus1999/RovexQuant/pull/40
- Exact head after the emulator-test adjustment: `0033a7032a90765ee51f945a89a104d4fd550c0f`
- Previous exact-head CI run #2538 passed backend tests, Android unit tests, Android compile/lint/APK integrity, container/auth smoke, NSE MCP connectivity, PostgreSQL migration/vault, static quality and security checks. One emulator test failed during `performScrollToNode` semantics traversal with a Compose `SnapshotStateObserver` cross-thread exception.
- The test now scrolls the Research Lab `LazyColumn` by stable item indices 6 and 7. CI run #2540 must pass before the PR is ready.
- Automatic refresh currently runs when Research Lab is opened (or manually requested) with a four-hour due interval; it does not run while the app is closed. WorkManager scheduling is not claimed by this PR.
- AI does not generate or fill prices. Market data must come from a permitted provider/source and pass deterministic validation.

### IME/window-resize fix

The single-purpose ahead branch `copilot/fix-android-instrumentation-ime-focus` changes only the main activity's soft-input behavior. This reconciliation branch ports its intended manifest setting:

`android:windowSoftInputMode="adjustResize|stateHidden"`

This allows the activity window to resize for the keyboard and avoids opening the activity with the keyboard already shown. It is isolated from the CSV PR.

## Explicitly not ported

- `feature/pulse-interactive-research`'s `NiftyIndicesOhlcvClient` uses an undocumented POST contract on the NSE Indices website. Current main already routes supported index history through the Upstox V3 historical API. Do not add the undocumented scraper or the stale `NSE_INDICES_PORTAL` provider path.
- `feat/quant-phase1-engineering-foundations` adds a Kotlin-daemon metadata pruning script and test, but the branch workflow only tests the script; it does not establish a safe, necessary production invocation. Do not mutate dependency-verification metadata without a proven failure and a reproducible validation path.
- `fix/android-emulator-research-navigation-test` has older workspace labels and test assumptions that do not match the current main UI.
- Snapshot branches `snapshot/ai-merge-base` and `snapshot/p2-merge-base` contain merge-base workflow snapshots, not user-facing product functionality.
- `fix/p1-auth-token-hardening` and `fix/p4-macro-refresh-health` have no commits ahead of main in the live comparison.

## Open PR disposition

- PR #40 remains the focused CSV/Bhavcopy PR and is not merged.
- PR #27 (`integration/quant-ai-research-history-mainline`) is 84 commits ahead and 561 behind main, and GitHub reports it unmergeable. Its main feature families are already represented on current main. Do not merge this stale aggregate branch; any genuinely missing hunk must be isolated and ported against current main with tests.
- No branch or PR is merged into `main` by this reconciliation work.

## Acceptance rule

Only changes based on current main, with source-level review and passing exact-head backend, Android unit, APK, security, PostgreSQL and emulator checks, should be promoted. A branch's historical commit count or ahead count is not an acceptance criterion.
