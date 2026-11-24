# P1 — Canonical instrument identity and historical OHLCV routing

Status: implementation candidate; provider runtime validation is pending.

## Canonical identity

Every historical series is keyed by exchange, instrument type, canonical symbol, and interval. The current daily interval is `1d`.

- Equity: TejHQ keyless NSE/BSE end-of-day OHLCV.
- Broad-market index: NSE Indices historical portal.
- Sector index: NSE Indices historical portal.
- Unknown NIFTY-like symbols fail closed; they are never passed to the equity endpoint.

The current explicit index allowlist includes NIFTY 50, NIFTY BANK, and the sector indices listed in `InstrumentIdentity.kt`. NIFTY BANK is a sector index, not an equity or broad-market index.

## Provider semantics

TejHQ documents `GET /v1/ohlcv/{exchange}/{symbol}` for NSE/BSE equities. Its documentation says it does not publish NSE Indices index values. The TejHQ endpoint returns raw end-of-day OHLCV; this implementation does not claim corporate-action adjustment.

NSE Indices publishes historical index OHLC through its historical-data portal. The portal's underlying `Backpage.aspx/getHistoricaldatatabletoString` request is not documented as a supported public API. The Android adapter therefore:
- is isolated from the equity client;
- uses only registry-controlled index names;
- fetches date windows no longer than 365 inclusive calendar days;
- uses a bounded response size and row count;
- validates dates and OHLC consistency;
- rate-spaces multi-window requests;
- does not bypass access controls or bot challenges;
- reports a provider error rather than substituting equity data.

This portal adapter remains an integration risk until verified against real NIFTY 50 and NIFTY BANK responses on a device. If the portal changes or denies the request, use the official portal manually rather than adding an undocumented bypass.

Index OHLC does not include traded volume in the historical portal response. The internal zero volume sentinel is never displayed as actual volume; the series metadata marks volume unavailable and the UI suppresses the volume control.

The index values are Price Return Index values, not Total Return Index values. Dividend reinvestment must not be inferred.

## Cache and UI

Typed cache filenames include exchange, instrument type, interval, and sanitized canonical symbol. Old equity cache files remain readable as a migration fallback. Legacy imported CSV data is not silently attached to an index identity.

The instrument screen shows source, interval, adjustment semantics, retrieval timestamp when known, date range, and explicit volume availability. Cached history remains visible during refresh; refresh errors are surfaced without silently replacing it.

## Acceptance still required

1. Android compile, lint, unit tests, and APK integrity on the exact commit.
2. Live provider smoke for NIFTY 50 and NIFTY BANK on Android, including response schema and data-date validation.
3. Ordinary NSE and BSE equity history regression against TejHQ.
4. Unsupported-symbol and cache-separation tests.
5. Physical-device interaction validation on the target Android device.

A successful CI build does not prove that the NSE Indices portal permits or serves the undocumented request contract.
