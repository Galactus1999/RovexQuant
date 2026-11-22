# Automatic NSE market-data collection

After a Quant app session is restored or a user signs in, the workspace loads the official Upstox NSE BOD instrument master, resolves dashboard index keys, loads the NSE cash-equity universe, and opens one authenticated Quant WebSocket. The app subscribes to all returned NSE cash equities plus its resolved index benchmarks in LTPC mode. Quotes are decoded from Upstox V3 Protobuf on the backend and forwarded as normalized ticks to Android. Market Pulse renders the official equity master and updates each row only when a tick for that instrument arrives.

Upstox Market Data Feed V3 requires its JSON subscription control payload to be sent as a WebSocket **binary** frame. Sending it as a text frame can leave a connection open without a working subscription. Quant sends UTF-8 bytes. The authorize-URL endpoint requires `Accept: application/json`.

Quant uses LTPC mode for the broad NSE cash-equity universe. Upstox documents an individual LTPC limit of 5,000 instrument keys; Quant rejects an oversized subscription rather than silently truncating it. Full-depth mode has a lower limit and is not used for this broad subscription.

The Quant username/password authenticates to the Quant backend, not Upstox. A valid owner-generated Upstox Analytics Token must be provisioned server-side in the encrypted credential vault or `UPSTOX_ANALYTICS_TOKEN`. The Analytics Token is read-only and supports WebSocket and Market Quote APIs. If absent, expired or rejected, the stream reports a provider error and no prices are fabricated.

This is a private owner-authorized NSE market-data display. It does not imply BSE, derivatives or non-equity coverage. The instrument master is reference metadata, not prices. Exchange and receive timestamps remain distinct. CI uses fake credentials and protocol fixtures; it does not prove a production token, Render deployment, entitlement or live exchange session.
