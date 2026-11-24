# RovexQuant engineering research decisions: authentication and instrument research

Date: 2026-11-24

This decision record is a mandatory pre-implementation gate for the active authentication and instrument-research work. A feature is not accepted merely because it compiles or looks current; it must have a traceable evidence base, measurable behaviour, and regression coverage.

## 1. Authentication

### Primary references
- IETF RFC 9700, OAuth 2.0 Security Best Current Practice: https://www.rfc-editor.org/rfc/rfc9700.html
- Android Developers, Show a biometric authentication dialog: https://developer.android.com/identity/sign-in/biometric-auth
- Android Developers, Android Keystore system: https://developer.android.com/privacy-and-security/keystore
- OWASP MASVS-AUTH: https://mas.owasp.org/MASVS/07-MASVS-AUTH/
- OWASP MASTG Android data storage: https://mas.owasp.org/MASTG/0x05d-Testing-Data-Storage/

### Adopt
- Keep access tokens short-lived and scoped to the mobile API.
- Issue high-entropy opaque refresh tokens; persist only a cryptographic hash server-side.
- Rotate refresh tokens atomically on every successful refresh and retain token-family lineage.
- Treat reuse of a consumed token as a compromise signal and revoke the active family.
- Enforce absolute and inactivity expiry, server-side logout/revocation, and password/security-event revocation.
- Keep all database writes transactional; do not create or mutate production schema from request handlers.
- Use HTTPS only; never log credentials, access tokens, refresh tokens, or authorization headers.
- On Android, protect the refresh credential using Android Keystore AES-GCM with authentication required for decryption, exposed through AndroidX BiometricPrompt CryptoObject. Handle key invalidation after biometric enrollment changes by requiring password login and safely replacing the key.
- Keep password login as recovery. Never persist the account password.
- Make refresh single-flight on the client so concurrent 401 responses cannot race token rotation.

### Explicitly reject
- Extending the current access-token TTL to simulate persistent login.
- A biometric UI-only gate that does not cryptographically protect the refresh credential.
- Plaintext refresh-token storage, token logging, client-side-only revocation, or silent fallback to an unprotected credential.
- Introducing a full third-party identity provider solely to solve local app unlock; it would add deployment and operational complexity without a current requirement.

### Acceptance tests
- Valid login, wrong password, expiry, refresh success, rotation, consumed-token replay, family revocation, logout, expired refresh, malformed token, DB outage, concurrent refresh race, password-change revocation.
- Android: biometric success/cancel/failure, no enrolled biometric, device credential fallback policy, Keystore invalidation, corrupted ciphertext, process restart, app reinstall/key loss, refresh failure and password recovery.
- Existing login, session restore, sign-out and navigation emulator tests must remain green.

## 2. Instrument research and OHLCV

### Adopt
- Use a multi-series local store keyed by (exchange, canonical instrument identifier, timestamp, interval, adjustment mode, provider) rather than a single-symbol cache.
- Preserve source, retrieval time, data mode, adjustment status, and quality flags. Never merge data from different symbols, exchanges, intervals, or incompatible adjustment modes.
- Render candlesticks and volume from actual OHLCV bars; provide date-range selection, crosshair/selected-bar OHLC details, zoom/pan, and indicator overlays.
- Begin with widely used, explainable technical studies: SMA/EMA, RSI (Wilder), MACD, ATR (Wilder), VWAP only where intraday volume/time data supports it, and Bollinger Bands. State parameter defaults and warm-up periods.
- Keep calculations deterministic and locally reproducible; separate adjusted from unadjusted series and never imply a signal is a recommendation.
- Validate OHLC invariants, timestamp ordering/timezone, duplicates, missing sessions, non-positive prices, volume bounds, and provider freshness before charting.
- Support CSV export of the exact visible/filtered series with provenance metadata.

### Newer ideas to evaluate, not blindly import
- Lightweight Canvas/custom drawing or a proven chart library: benchmark interaction latency, accessibility, lifecycle and memory before selection.
- Incremental indicator recomputation: adopt only after profiling shows full-series recomputation is a real bottleneck.
- Corporate-action-adjusted total-return views: keep as a separately labelled series and verify adjustment source and methodology.
- Event overlays and point-in-time sector/constituent context: require timestamped evidence and historical membership, not current constituents retroactively applied.

### Explicitly reject
- Decorative charts with fabricated or fixture values in production.
- Unverified index aliases or treating an index as an equity ticker.
- Mixing TejHQ, CSV, Upstox or other providers without retaining provenance and adjustment semantics.
- Adding fashionable indicators or ML predictions without a stated hypothesis, benchmark, out-of-sample evaluation and reproducible calculation.

### Acceptance tests
- Multi-symbol and multi-exchange persistence, duplicate upsert, incremental import, same-date multi-interval records, source separation, adjustment separation, corrupt CSV, malformed OHLC, timezone boundaries and cache identity.
- Chart range/filter/indicator changes must update the rendered series; tapping a candle must show that exact bar; no-data and stale-data states must be explicit.
- Index routing must be provider-verified; unsupported instruments must show an explicit unsupported state rather than an empty or misleading chart.

## 3. Research gate for future changes

Every non-trivial feature PR must include:
1. Primary-source/official documentation and, where relevant, standards or peer-reviewed/established references.
2. At least one comparison against a mature, widely adopted implementation or current alternative.
3. A reason to adopt, defer, or reject each candidate.
4. Threat/performance/data-integrity analysis.
5. Acceptance criteria and regression tests.
6. Evidence from CI and, where data/provider/device behaviour is involved, a real integration/device check.

Popularity or social-media trend alone is not sufficient evidence. Prefer boring, proven components where they meet the requirement; adopt newer approaches only when they deliver measured benefits.
