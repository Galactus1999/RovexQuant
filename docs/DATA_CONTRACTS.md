# Data contracts v1

- Instrument: stable internal UUID, exchange, segment, symbol, company, ISIN, status and validity dates. Provider tokens are separate provider-specific mappings; Zerodha tokens and Dhan security IDs are never interchangeable.
- Bar: instrument, timeframe, start/end, OHLCV, provider, exchange timestamp, received timestamp, complete flag, adjustment status and quality.
- Quality: VALID, DELAYED, STALE, INCOMPLETE, GAP_DETECTED, PROVIDER_DISCONNECTED, ADJUSTMENT_PENDING.
- Signal: UUID, instrument, strategy/version, timeframe, direction, status, score, entry, stop, targets, R:R, generated/data-as-of/expiry timestamps, invalidation and evidence. Immutable facts; append lifecycle events.
- Risk profile: configurable capital INR, risk fraction, daily loss cap, max positions, sector exposure, minimum R:R and small-cap/short permissions.
- Store UTC instants and exchange timezone context. Model holidays, special sessions, pre-open, halts and circuits.
