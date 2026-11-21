# Roadmap

0. Foundation: provider-neutral contracts, module boundaries, CI, local mode.
1. Historical research: permitted CSV/bhavcopy import, instrument master, corporate actions, data quality, daily/swing scanner.
2. Backtesting/paper research: event-driven simulator, realistic costs/slippage, walk-forward validation and signal lifecycle.
3. Intraday: authorised live/delayed source, session-aware candle builder, reconnect/replay and provider health.
4. Optional Zerodha/Dhan data adapters behind the same provider port; verify official docs and data-use permissions. No order endpoints.
5. AI research assistant: DeepSeek, Groq, OpenRouter adapters with secure user configuration; evidence-grounded explanations only.
6. Production hardening: Android persistence, offline cache, observability, security review, benchmark and release validation.

Current implementation is an alpha research prototype. UI, indicators and a simplified long-only backtester exist; no live market feed, full scanner scheduler, persistence, broker adapter or AI API execution is enabled.
