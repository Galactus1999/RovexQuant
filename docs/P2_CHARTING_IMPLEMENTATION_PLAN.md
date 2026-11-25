# P2 — Charting and Technical-Analysis Implementation Plan

Status: implementation phase opened; no chart-library dependency has been added yet.

## Baseline and constraints

- Base branch: `main` at the current P1 merge baseline (`e07c74edf1a600f1c33e64411733fcdd0d548940`).
- P1 instrument identity/provider routing remains authoritative. Do not fork identity resolution or provider selection inside chart code.
- Android UI must call `QuantDataRepository`; `QuantRepository` owns IO dispatch and provider coordination; `QuantApi` remains transport-only.
- Deterministic calculations remain the backend/domain owner's responsibility. Chart JavaScript only renders validated, typed series.
- Research-only product: no order placement or trading execution.
- Every series must retain source/provider, instrument identity, interval, adjustment basis, data mode, timestamps and quality/freshness semantics.

## Research decision: renderer

**Preferred candidate: TradingView Lightweight Charts Android wrapper, `com.tradingview:lightweightcharts:5.2.1`.**

Evidence reviewed:
- TradingView's Android wrapper documentation says it renders Lightweight Charts in Android WebView, requires Android System WebView/Chrome WebView with ES2020 support, and supports minSdk 23. RovexQuant minSdk is 26.
- The official Android wrapper README documents Maven Central publication and 5.2.1, with v5 multi-pane, marker, range and series APIs.
- Maven Central publishes the Android AAR as Apache-2.0 and lists its transitive runtime dependencies.
- Lightweight Charts is a small Canvas-based library; official documentation covers Candlestick, Line, Histogram and multi-pane series, crosshair and visible logical ranges.
- License requires TradingView product attribution and a link to tradingview.com in the app. Include the NOTICE attribution; do not omit it.
- TradingView Advanced Charts/Trading Platform are not licensed for personal hobby/research use; do not attempt to use them. Lightweight Charts is the appropriate open-source product.

Before dependency adoption, verify the exact AAR API and transitive dependency compatibility against the repository's pinned Kotlin/AGP, strict Gradle dependency locks and verification metadata. The published POM lists Kotlin stdlib 2.2.10, AndroidX WebKit 1.14.0 and Gson 2.13.2; this must be reconciled with current locks and runtime license allowlist. Do not weaken strict verification or license checks. If the wrapper conflicts with the pinned toolchain, evaluate a local bundled JS bridge or a native Compose Canvas fallback with an explicit comparison; do not silently downgrade.

## P2 work packages

### P2.0 — baseline/source and dependency spike
- Inspect current instrument research screen, navigation, repository, typed OHLC series, backend indicator contracts and existing tests.
- Confirm P1 index/equity instrument routes and verify no chart path bypasses the repository.
- Build a minimal isolated wrapper spike on a dedicated branch; check AAR API signatures, WebView lifecycle, cold-start cost, memory, accessibility, offline rendering and Android 15 emulator behavior.
- Record exact dependency tree, license inventory and APK size delta before adopting.

### P2.1 — typed chart contract and validation
- Add a chart presentation contract derived from the existing `MarketOhlcvSeries`, not a second instrument/provider model.
- Validate strictly increasing unique timestamps, finite positive OHLC, `high >= max(open, close)`, `low <= min(open, close)`, `high >= low`, and bounded point count.
- Preserve raw versus adjusted basis and provider/source timestamps.
- Keep index volume unavailable when provider has no volume. Do not create zero-valued or synthetic volume bars.
- Handle gaps, stale/partial history and empty/error states explicitly; do not interpolate missing market bars.
- Convert date/time values without milliseconds/seconds confusion or timezone drift. Daily candles use the exchange trading date; intraday candles preserve exact instant and interval.

### P2.2 — interactive primary chart
- Candlestick and OHLC/bar chart modes; optional close line.
- Pan, pinch zoom, crosshair, visible date/price readout, fit-content/reset and selected-range controls.
- OHLC details on crosshair; show source, data mode, as-of time, date range and adjustment basis.
- Incremental updates use series update for the last bar; do not replace the entire series for every tick.
- Keep the chart bounded and responsive for large histories; do not push large JSON/string conversion or indicator calculation onto the UI thread.

### P2.3 — indicator overlays and panes
- Overlays: SMA, EMA and Bollinger Bands.
- Separate panes: RSI, MACD and ATR/ADX where the current backend contract returns those series.
- Add volume histogram only when `volumeAvailable=true` and valid per-bar volume is present.
- Indicator periods and warm-up/null regions must be visible and mathematically consistent with the deterministic engine. Never forward-fill warm-up values.
- Overlay and pane toggles must change the actual rendered series, not be decorative controls.

### P2.4 — comparison, events and usability
- Compare instruments only after compatible exchange, interval, timestamp, currency and adjustment semantics are checked; normalized percentage/indexed comparison must be labelled.
- Event/corporate-action markers must include source and event date; distinguish known events from inferred hypotheses.
- Fullscreen, theme adaptation, accessibility labels, touch target sizes and orientation/fold/split-screen behavior.
- TradingView attribution and NOTICE included in a visible About/Credits surface.

## Formula and calculation ownership

- Reuse existing backend indicator implementations and their tested contracts. Do not introduce a second Kotlin/JavaScript formula implementation as the source of truth.
- The current backend already has deterministic SMA, EMA, RSI, MACD, Bollinger Bands, ATR and ADX calculations. Audit warm-up and smoothing semantics before chart integration.
- Wilder-style smoothing, seed conventions and period defaults must be explicit in tests; compare output against hand-calculated fixtures and a trusted independent reference. Chart display must preserve null warm-up points.
- Correlation is descriptive, not causal. No indicator or chart overlay is a buy/sell recommendation.

## Security, privacy and data rights

- Chart JS must be packaged locally; no remote script/CDN dependency at runtime.
- WebView must not navigate to arbitrary external pages, enable file/content access unnecessarily, expose a broad JavaScript interface, or accept untrusted HTML.
- Pass bounded typed data through the wrapper API; avoid string-built executable JavaScript.
- Do not send restricted market data to an external AI model without the applicable data-use permission.
- Follow `docs/MARKET_DATA_POLICY.md`. Personal-use status does not override NSE non-display policy, provider agreements or exchange rights.

## Test and acceptance matrix

1. Unit tests: OHLC validation, sorted/unique timestamps, timezone/date conversion, gaps, null warm-up values, volume availability, index-without-volume, raw/adjusted separation and point-count limits.
2. Indicator tests: SMA/EMA/RSI/MACD/Bollinger/ATR/ADX against hand-computed fixtures, edge cases and backend contract fixtures.
3. Repository tests: instrument identity preserved; NSE indices continue through NSE Indices provider; equities continue through TejHQ; no provider fallback across incompatible instrument types.
4. Renderer tests: empty/error/loading/stale states; candlestick/line/volume visibility; indicator toggles; crosshair data; range reset; marker metadata; chart disposal/recreation.
5. Android emulator: login → instrument search → open chart → change interval/date range → pan/pinch → toggle indicators → fullscreen/back → navigate away/reopen → sign out. Include index with no volume and equity with volume.
6. Performance: measure cold chart initialization, 1k/5k/10k bar rendering, incremental last-bar update, memory and jank on Android 15 emulator and OnePlus CPH2691 Android 16 if available.
7. CI: strict dependency verification, license allowlist, compile/lint, APK integrity, emulator UI tests, security/secret checks and existing Phase 1 acceptance gates. Do not claim device validation from emulator results alone.

## Definition of done

P2 is complete only when the chart is connected to real validated P1-routed history, chart interactions and indicator controls work, volume is correctly conditional, data provenance/quality is visible, Android lifecycle and security tests pass, and exact-head CI is green. A renderer demo or static screenshot is not acceptance.


## Primary technical references reviewed

- TradingView Lightweight Charts getting started and series data: https://tradingview.github.io/lightweight-charts/docs/5.2
- TradingView Android wrapper and WebView requirements: https://tradingview.github.io/lightweight-charts/docs/5.2/android
- Android wrapper API, Maven coordinate, asynchronous bridge error listener and attribution: https://github.com/tradingview/lightweight-charts-android
- Maven Central artifact metadata, AAR version, Apache-2.0 license and declared transitive dependencies: https://central.sonatype.com/artifact/com.tradingview/lightweightcharts
- Lightweight Charts time-scale visible ranges and crosshair interaction: https://tradingview.github.io/lightweight-charts/docs/time-scale
- Lightweight Charts multi-pane API: https://tradingview.github.io/lightweight-charts/docs/panes
- TradingView product licensing distinction: https://www.tradingview.com/free-charting-libraries/
- Wilder RSI method and smoothing: https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/relative-strength-index-rsi
- Wilder ADX method and warm-up implications: https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/average-directional-index-adx
- TA-Lib indicator catalogue: https://ta-lib.org/functions/
- Reference textbook: John J. Murphy, *Technical Analysis of the Financial Markets*, publisher page: https://www.penguinrandomhouse.com/books/350647/technical-analysis-of-the-financial-markets-by-john-j-murphy/

### Source-audit finding: indicator transport contract

The current backend exposes authenticated `POST /api/v1/research/indicators`. It accepts aligned close/high/low arrays up to 10,000 observations, configurable SMA/EMA/RSI/Bollinger/ATR/ADX periods, and returns aligned deterministic arrays; MACD is also returned. ATR and ADX are emitted only when both high and low arrays are supplied. This route is already rate-limited. P2 Android integration must add a typed repository/API call rather than duplicate indicator formulas in chart JavaScript. The chart history limit (100,000) is therefore distinct from the current indicator request limit (10,000); the UI must disclose the calculation window or use a bounded visible-window request with sufficient warm-up history. Do not silently pretend that indicators cover more history than was submitted.

The Android wrapper executes chart calls asynchronously in WebView. Its documentation warns that bridge errors may otherwise be thrown on the main thread and crash the app. Any adopted wrapper integration must register and test its error listener, make disposal/recreation lifecycle-safe, and verify that a stale callback cannot update a disposed chart.


## Implementation audit — 24 November 2026

### Findings in the pre-P2 instrument screen
- The existing instrument research chart was a close-price polyline, not OHLC candlesticks. It did not render open/high/low candle bodies.
- The old Canvas path iterated every visible source bar; selecting ALL could expose the renderer to the full 100,000-bar storage limit.
- The on-screen text advertised drag-to-inspect although selection was tap-based; pan was implemented through transform gestures.
- The typed chart mapper was initially not connected to the instrument chart or cache persistence path.
- The P1 sync manager could merge cached and newly fetched rows and persist them without chart-contract validation first.

### Repairs now present on the P2 branch
- Added renderer-neutral `ChartSeriesData` and strict `ChartSeriesMapper` validation, including Asia/Kolkata exchange-date normalization, OHLC invariants, ordered/unique dates, maximum history and volume-source consistency.
- Connected the instrument research screen to mapped/validated chart data.
- Added a native Compose OHLC candlestick renderer with wicks, candle bodies, SMA 20/50 overlays, volume only when supplied, OHLC inspection, crosshair, pinch zoom, pan and reset/overview controls.
- Indicator arrays are computed on `Dispatchers.Default`, not in the Compose draw path.
- Capped the visible render window at 2,000 candles to prevent an ALL-range gesture from forcing a 100,000-element Canvas draw; older history remains accessible by panning.
- The history sync manager validates cached history, fetched rows and the merged result before persistence. A malformed cache is discarded for the incremental merge path so the requested history is fetched afresh. An empty incremental response preserves a valid existing cache.
- Removed the duplicate interim candlestick renderer after selecting the validated-data renderer.

### Still incomplete — do not mark P2 complete
- The preferred TradingView Android wrapper has not been added. The native Compose renderer is an interim, dependency-free implementation pending a controlled wrapper compatibility spike.
- SMA 20/50 are the only currently wired chart overlays. EMA, Bollinger Bands, RSI, MACD, ATR and ADX are not yet integrated into Android chart panes.
- The Android client does not yet call the backend `/api/v1/research/indicators` endpoint through a typed repository/API contract.
- No Android renderer interaction tests, index/equity chart emulator test, real-device performance profile, fullscreen, multi-instrument comparison or event markers have passed yet.
- The exact latest branch head must pass a fresh complete CI run. An earlier run was cancelled after subsequent branch pushes; do not treat it as verification of the latest head.
