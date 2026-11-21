# RovexQuant

Independent, provider-agnostic Indian-market research terminal. Signals, alerts and manual research only—no broker order placement.

## Current alpha scope

### Android
- Dark, spacious Jetpack Compose terminal with animated bottom navigation.
- Separate Pulse, Strategy Scanner, Signals, Research Lab and Portfolio/Risk workspaces.
- Strategy horizon selector: scalping, intraday, swing, positional and investment.
- Clear demo/offline states; no fabricated market prices or chart candles.

### Backend
- FastAPI research API and versioned strategy catalogue.
- Indicators: SMA, EMA, RSI, MACD, ATR, ADX, session VWAP and OBV/relative volume.
- Research strategies: SMA crossover, EMA alignment, RSI regime, Donchian breakout, volume-confirmed breakout, Bollinger re-entry, opening-range breakout and session VWAP cross.
- Long-only event-driven backtest prototype with next-bar-open entries, stop handling, configurable commission and slippage.
- Strict local OHLCV CSV importer for timezone-aware user-supplied datasets, with conservative intraday gap detection.

## Run backend locally

From the repository root:

```bash
cd backend
python -m pip install -e ".[dev]"
pytest -q
uvicorn rovexquant.api.main:app --reload
```

API endpoints:
- GET /api/v1/health
- GET /api/v1/strategies
- POST /api/v1/research/indicators
- POST /api/v1/research/backtest

## Important limitations

This is an alpha research prototype, not a live trading system. No live market feed, broker adapter, persistent scanner, alert delivery, Android/backend networking, local database, AI provider execution or order endpoint is enabled. The backtester is long-only and does not yet model market impact, partial fills, queue position, exchange-specific taxes/levies, short borrow or derivatives margin.

Use only datasets whose licences permit the intended analysis. NSE public web visibility is not a data licence; see docs/MARKET_DATA_POLICY.md. Never label delayed or sample data as live. Strategy performance is unproven; out-of-sample and walk-forward validation are mandatory before any use beyond research.

## Repository layout
- android/: Kotlin / Jetpack Compose client
- backend/: FastAPI research engine
- docs/: architecture, contracts, market-data policy, strategy catalogue, security and roadmap

## Zero-budget design
Start with local development and permitted historical data. Free cloud hosting is optional and may sleep or lose persistent workers; live scanning must pause visibly when data or infrastructure is unavailable.
