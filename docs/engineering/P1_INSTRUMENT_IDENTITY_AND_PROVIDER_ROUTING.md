# P1 — Canonical instrument identity and historical OHLCV routing

Status: implementation candidate; exact-head CI and provider/device runtime validation are pending.

## Canonical identity

Every historical series is keyed by exchange, instrument type, canonical symbol, and interval. The current daily interval is `1d`.

- Equity: TejHQ keyless NSE/BSE end-of-day OHLCV.
- Broad-market index: NSE Indices historical portal.
- Sector index: NSE Indices historical portal.
- Unknown NIFTY-like symbols fail closed; they are never passed to the equity endpoint.

The explicit index allowlist includes NIFTY 50, NIFTY BANK, and the sector indices listed in `InstrumentIdentity.kt`. NIFTY BANK is a sector index. The Market Pulse display alias `NIFTY OIL & GAS` maps to the official portal name `NIFTY OIL AND GAS INDEX`.

## Provider semantics

TejHQ documents `GET /v1/ohlcv/{exchange}/{symbol}` for NSE/BSE equities. Its documentation says it does not publish NSE Indices index values. The TejHQ endpoint returns raw end-of-day OHLCV; this implementation does not claim corporate-action adjustment.

NSE Indices publishes historical index OHLC through its historical-data portal. The portal's underlying POST contract is not documented as a supported public API. This adapter is deliberately isolated, allowlisted, rate-spaced, bounded and fail-closed. It does not bypass bot challenges or access controls.

Index history is Price Return Index OHLC, not Total Return Index. The portal response does not supply traded volume. The internal zero sentinel is never presented as traded volume; series metadata marks volume unavailable and the UI suppresses the volume control.

## Cache and UI

Typed cache identity includes exchange, instrument type, interval and canonical symbol. Old equity cache files remain readable as a migration fallback. Legacy imported CSV data is not silently attached to an index identity.

The instrument screen shows source, interval, adjustment semantics, retrieval timestamp when known, date range and explicit volume availability. Cached history remains visible during refresh; refresh errors are surfaced without silently replacing cached data. The default load retrieves three years; selecting ALL requests the provider's bounded 6,200-day history as a non-incremental backfill, so older cache rows are not mistaken for complete history. The ALL state reports the earliest locally available date.

The current Compose Canvas chart supports selected-bar OHLC inspection, drag/pan, pinch zoom and reset. SMA 20/50 values are computed once per bar-set using a rolling sum rather than allocating sublists during each draw. This is an interaction improvement, not a replacement for the planned full chart engine.

## Acceptance still required

1. Android compile, lint, unit tests and APK integrity on the exact commit.
2. Live provider smoke for NIFTY 50 and NIFTY BANK on Android, including response schema and data-date validation.
3. Ordinary NSE and BSE equity history regression against TejHQ.
4. Unsupported-symbol and cache-separation tests.
5. Physical-device interaction validation, including ALL-history backfill, chart pan/zoom, crosshair selection and reset.

A successful CI build does not prove that the NSE Indices portal permits or serves the website-backed request contract.
