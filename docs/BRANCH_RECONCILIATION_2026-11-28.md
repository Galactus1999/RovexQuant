# RovexQuant ahead-branch reconciliation — 2026-11-28

## Final integration baseline

- Repository: `Galactus1999/RovexQuant`
- Current `main`: `09db8ed71aa91e46b1f1796f777b5a24279fb06f`
- PR #40 squash merge: `c38762855c11229a9c88867dac9d23c13b834367`
- PR #41 squash merge: `09db8ed71aa91e46b1f1796f777b5a24279fb06f`
- Both PRs are closed as merged. Their exact-head required CI checks passed.
- The initial PR #40 emulator attempt failed transiently with a Compose SnapshotStateObserver cross-thread exception; the targeted retry passed. No test was weakened.
- PR #41's exact-head workflow run #2543 passed Android emulator regression, Android compile/lint/APK integrity, unit/backend, static quality, container/auth, NSE MCP, PostgreSQL, security/API/architecture, secret scan and the required Phase 1 acceptance gate.

## Method and decision rule

Commit-ahead count is not proof that a branch contains missing functionality. The audit compared branch histories and changed-file inventories, then inspected relevant source files on current `main` and candidate branches. Old branches were not merged wholesale: they frequently have large divergence from the current mainline and contain earlier implementations, prototypes, or duplicated functionality.

Only distinct, current-main-compatible changes were promoted. Every promoted change was isolated in a focused PR and required exact-head CI before merge.

## Functionality represented on current main

The following branch families were reviewed. Their principal feature families are already represented on current main in newer or safer forms, or the branch is a stale aggregate rather than a clean integration candidate:

- AI provider discovery, server-side routing, AI research tools, claim verification, and persistent research history: `feat/ai-auto-provider-discovery`, `feat/ai-integrations-studio`, `feat/ai-intelligence-tool-registry`, `feat/ai-research-history-ledger`, `integration/ai-studio-mainline`, `integration/ai-studio-mainline-v2`, `integration/quant-ai-research-history-mainline`, `integration/quant-ai-research-mainline`.
- Instrument identity, company/index search, index/sector research and validated charts: `feature/pulse-interactive-research`, `fix/p1-instrument-identity`, `feature/p2-indicator-integration`.
- EOD persistence, ingestion worker, macro observations, AMFI NAV, event studies and research-data health: `fix/p1-market-data-flow`, `feat/research-data-connectors-launcher`, `fix/quant-phase1-nse-mcp-live-smoke`.
- Bounded Upstox market-stream handling: `feat/p0-bounded-market-stream`. Current main has bounded frame ingress and chart-flow handling; the older branch version is not a safe replacement.
- Backtesting, indicator engine foundations, portfolio/risk screens and quality UI: `feat/quant-engine-quality-ui-expansion`. Current main has the more complete backtesting and portfolio/risk implementation.
- Mainline visual workspace and Market Pulse functionality: `feat/frontend-visual-first`. Its older helper/UI implementations are not a standalone feature worth replacing current screens with.
- The older `QuantTerminal` prototype from `feat/premium-quant-terminal-ui` is a disconnected demo with placeholder prices/charts and “awaiting feed” content. It must not replace the live data-backed workspace.
- The old research connector branch contains earlier workspace and Market Pulse implementations; current main has more complete provider, EOD and copilot integration.
- `fix/quant-phase1-nse-mcp-live-smoke`'s inspected `nse_mcp.py` was byte-for-byte identical to main in the reviewed path.

### Source-level checks on representative candidates

- `InstrumentIdentity.kt` on main is 6,899 characters versus 5,587 characters on `fix/p1-instrument-identity` / `feature/pulse-interactive-research`; main's blob is different and more developed.
- `TejHqOhlcvStore.kt` on main is 8,830 characters versus 8,571 on those branches.
- `TejHqOhlcvClient.kt` is identical between main and those branches.
- `InstrumentResearchScreen.kt` on main is 39,531 characters versus 13,771 / 18,212 on the older branches.
- `fix/android-emulator-research-navigation-test` contains a 13,798-character regression-test file with 10 test methods; current main's 37,357-character file has 18 test methods and includes newer index/sector chart and AI provider-discovery coverage. Do not replace it with the older test file.
- `feat/quant-phase1-engineering-foundations` contains a Kotlin-daemon metadata pruning script and tests that are absent from main, but current main has a newer `verify_kotlin_daemon_alignment.py` validation integrated into CI. The branch workflow is older and should not replace current dependency-lock / verification-metadata handling.

## Distinct changes merged

### PR #40 — universal CSV/Bhavcopy ingestion

- PR: https://github.com/Galactus1999/RovexQuant/pull/40
- Merged commit: `c38762855c11229a9c88867dac9d23c13b834367`
- Adds universal CSV parsing, NSE/BSE UDiFF and legacy Bhavcopy handling, multi-security splitting, bounded ZIP/encoding support, imported-dataset refresh, a bounded authenticated bulk-history endpoint, per-dataset sync provenance and tests.
- CI run #2540: all required checks passed after a targeted retry of the Android emulator job. The first emulator attempt raised a Compose `SnapshotStateObserver` cross-thread exception; the retry passed without weakening or changing the test.
- Operating limit: refresh runs when Research Lab opens or when the user requests Sync Now, subject to login, network and source entitlement. It does not run while the app is closed. WorkManager scheduled background refresh remains follow-on work.
- AI does not invent or fill prices. Provider data remains authoritative and is validated deterministically.

### PR #41 — IME/window-resize fix and reconciliation record

- PR: https://github.com/Galactus1999/RovexQuant/pull/41
- Merged commit: `09db8ed71aa91e46b1f1796f777b5a24279fb06f`
- Ports `android:windowSoftInputMode="adjustResize|stateHidden"` to `.MainActivity`, enabling keyboard-aware window resizing and keeping the keyboard initially hidden.
- Adds this reconciliation record.
- Exact-head CI run #2543 passed all required checks, including Android emulator regression and the Phase 1 acceptance gate.

## Explicitly not ported

- `feature/pulse-interactive-research`'s `NiftyIndicesOhlcvClient` uses an undocumented POST contract on the NSE Indices website. Current main routes supported index history through the Upstox V3 historical API. Do not add the undocumented scraper or stale `NSE_INDICES_PORTAL` provider path.
- `feat/quant-phase1-engineering-foundations`'s metadata-pruning approach is superseded by current main's explicit Kotlin daemon alignment validation. Do not mutate dependency-verification metadata without a proven failure and reproducible validation path.
- `fix/android-emulator-research-navigation-test` has older workspace labels and test assumptions that do not match current main.
- Snapshot branches `snapshot/ai-merge-base` and `snapshot/p2-merge-base` contain merge-base workflow snapshots, not user-facing product functionality.
- `fix/p1-auth-token-hardening` and `fix/p4-macro-refresh-health` had no commits ahead in the earlier live comparison.
- PR #27 (`integration/quant-ai-research-history-mainline`) was a large, stale, unmergeable aggregate. Its principal AI/research feature families are already represented on current main. Do not bulk merge it; any newly proven missing hunk must be isolated and ported with tests.

## Remaining work

- Do not merge remaining ahead branches by commit count alone. Recompare against current main and port only a demonstrably missing, non-regressive feature.
- The CSV refresh feature currently lacks closed-app scheduled execution. If that requirement is pursued, implement it as a separate WorkManager/backend-scheduler change with unique work, network constraints, bounded retries, cancellation, provenance/freshness and observable sync state.
- Continue reproducing and fixing any still-open product defects (index/sector chart details, automatic data refresh behavior, emulator regressions) against current main rather than restoring old branch snapshots.

## Acceptance rule

Only changes based on current main, with source-level review and passing exact-head backend, Android unit, APK, security, PostgreSQL and emulator checks, should be promoted. A branch's historical commit count or ahead count is not an acceptance criterion.
