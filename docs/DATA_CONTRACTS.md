# Data contracts v1

- Instrument: stable internal UUID, exchange, segment, symbol, company, ISIN, status and validity dates. Provider tokens are separate provider-specific mappings; Upstox instrument keys and Dhan exchange-segment/security IDs are never interchangeable.
- Quote snapshot: instrument symbol, last price, previous close, absolute/percentage change, volume, provider, exchange timestamp, receive timestamp and quality. A missing exchange timestamp is explicitly marked unavailable, never assumed current.
- Bar: instrument, timeframe, start/end, OHLCV, provider, exchange timestamp, received timestamp, complete flag, adjustment status and quality.
- Quality: VALID, DELAYED, STALE, INCOMPLETE, GAP_DETECTED, PROVIDER_DISCONNECTED, ADJUSTMENT_PENDING; quote adapters additionally distinguish TIMESTAMP_UNAVAILABLE, FUTURE_TIMESTAMP and INVALID_PRICE.
- Signal: UUID, instrument, strategy/version, timeframe, direction, status, score, entry, stop, targets, R:R, generated/data-as-of/expiry timestamps, invalidation and evidence. Immutable facts; append lifecycle events.
- Risk profile: configurable capital INR, risk fraction, daily loss cap, max positions, sector exposure, minimum R:R and small-cap/short permissions.
- Store UTC instants and exchange timezone context. Model holidays, special sessions, pre-open, halts and circuits.
