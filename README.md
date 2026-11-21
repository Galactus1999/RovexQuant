# RovexQuant

Independent, provider-agnostic Indian-market research and paper-trading platform. Signals only; no broker order placement.

## Principles
- Zero-cost/local development mode using permitted historical/public datasets.
- Provider-neutral market-data contracts; optional future Zerodha Kite Connect and DhanHQ adapters.
- Deterministic strategies and risk controls are authoritative. AI explains supplied evidence and cannot override risk gates.
- Small modules, explicit ports/adapters, tests and versioned strategies.

## Layout
- `android/`: Kotlin/Jetpack Compose client.
- `backend/`: FastAPI modular monolith.
- `docs/`: architecture, contracts, strategy catalogue, security and roadmap.

## Status
Starter foundation only. Not a live trading system. No broker connection, live feed or order placement is implemented. See `docs/ROADMAP.md`.