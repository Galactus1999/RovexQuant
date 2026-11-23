"""Deterministic event-window study over point-in-time EOD bars.

This reports observed price behaviour only. Without a matching benchmark series it
does not claim abnormal returns or causal event impact.
"""
from __future__ import annotations

from datetime import date
from math import isfinite, sqrt
from statistics import pstdev
from typing import Any


def calculate_event_window(
    bars: list[dict[str, Any]], *, event_date: date, pre_bars: int = 10, post_bars: int = 10,
    benchmark_bars: list[dict[str, Any]] | None = None,
    benchmark_symbol: str | None = None,
) -> dict[str, Any]:
    if not 1 <= pre_bars <= 60 or not 1 <= post_bars <= 60:
        raise ValueError("Event windows must be between 1 and 60 trading bars")
    ordered = sorted(
        (bar for bar in bars
         if isinstance(bar.get("date"), str)
         and isinstance(bar.get("close"), (int, float))
         and not isinstance(bar.get("close"), bool)
         and isfinite(float(bar["close"]))),
        key=lambda bar: bar["date"],
    )
    if len({bar["date"] for bar in ordered}) != len(ordered):
        raise ValueError("Asset history contains duplicate trading dates")
    before = [bar for bar in ordered if bar["date"] < event_date.isoformat()]
    on_or_after = [bar for bar in ordered if bar["date"] >= event_date.isoformat()]
    if not before or not on_or_after:
        raise ValueError("Insufficient EOD bars on both sides of the event date")
    anchor = before[-1]
    window = on_or_after[:post_bars]
    if not window:
        raise ValueError("No post-event observations are available")
    anchor_close = float(anchor["close"])
    if anchor_close <= 0 or any(float(bar["close"]) <= 0 for bar in window):
        raise ValueError("Event window contains non-positive close values")
    pre_window = before[-pre_bars:]
    pre_close = float(pre_window[0]["close"])
    pre_returns = [
        float(pre_window[i]["close"]) / float(pre_window[i - 1]["close"]) - 1.0
        for i in range(1, len(pre_window))
    ]
    returns = [float(bar["close"]) / anchor_close - 1.0 for bar in window]
    daily_returns = [
        float(window[0]["close"]) / anchor_close - 1.0,
        *[
            float(window[i]["close"]) / float(window[i - 1]["close"]) - 1.0
            for i in range(1, len(window))
        ],
    ]
    peak = anchor_close
    drawdown = 0.0
    for bar in window:
        close = float(bar["close"])
        peak = max(peak, close)
        drawdown = min(drawdown, close / peak - 1.0)
    fully_adjusted = bool(ordered) and all(bar.get("price_basis") == "CORPORATE_ACTION_ADJUSTED" for bar in ordered)
    benchmark_metrics: dict[str, Any] = {
        "benchmark_symbol": None,
        "market_adjusted_cumulative_return": None,
        "cumulative_abnormal_return": None,
        "benchmark_observations_matched": 0,
    }
    if benchmark_bars:
        benchmark_ordered = sorted(
            (bar for bar in benchmark_bars
             if isinstance(bar.get("date"), str)
             and isinstance(bar.get("close"), (int, float))
             and not isinstance(bar.get("close"), bool)
             and isfinite(float(bar["close"]))
             and float(bar["close"]) > 0),
            key=lambda bar: bar["date"],
        )
        benchmark_before = [bar for bar in benchmark_ordered if bar["date"] < event_date.isoformat()]
        benchmark_after = {bar["date"]: float(bar["close"]) for bar in benchmark_ordered
                           if bar["date"] >= event_date.isoformat()}
        if benchmark_before:
            benchmark_anchor = float(benchmark_before[-1]["close"])
            matched = [(bar, benchmark_after.get(bar["date"])) for bar in window]
            matched = [(bar, close) for bar, close in matched if close is not None]
            if benchmark_anchor > 0 and matched:
                benchmark_return = matched[-1][1] / benchmark_anchor - 1.0
                asset_return = float(matched[-1][0]["close"]) / anchor_close - 1.0
                daily_abnormal = []
                previous_asset, previous_benchmark = anchor_close, benchmark_anchor
                for bar, benchmark_close in matched:
                    asset_daily = float(bar["close"]) / previous_asset - 1.0
                    benchmark_daily = benchmark_close / previous_benchmark - 1.0
                    daily_abnormal.append(asset_daily - benchmark_daily)
                    previous_asset, previous_benchmark = float(bar["close"]), benchmark_close
                benchmark_metrics = {
                    "benchmark_symbol": benchmark_symbol,
                    "market_adjusted_cumulative_return": asset_return - benchmark_return,
                    "cumulative_abnormal_return": sum(daily_abnormal),
                    "benchmark_observations_matched": len(matched),
                }

    return {
        **benchmark_metrics,
        "event_date": event_date.isoformat(),
        "anchor_date": anchor["date"],
        "anchor_close": anchor_close,
        "first_post_event_date": window[0]["date"],
        "last_post_event_date": window[-1]["date"],
        "pre_event_bars_used": len(pre_window),
        "pre_event_period_return": anchor_close / pre_close - 1.0 if pre_close > 0 else None,
        "pre_event_realized_daily_volatility": pstdev(pre_returns) * sqrt(252) if len(pre_returns) > 1 else None,
        "post_event_bars_used": len(window),
        "cumulative_return": returns[-1],
        "best_close_return": max(returns),
        "worst_close_return": min(returns),
        "post_window_max_drawdown": drawdown,
        "realized_daily_volatility": pstdev(daily_returns) * sqrt(252) if len(daily_returns) > 1 else None,
        "price_basis": "CORPORATE_ACTION_ADJUSTED" if fully_adjusted else "RAW_OR_MIXED_CLOSE",
        "method": (
            "CORPORATE_ACTION_ADJUSTED_MARKET_EVENT_WINDOW"
            if fully_adjusted and benchmark_metrics["benchmark_observations_matched"]
            else "CORPORATE_ACTION_ADJUSTED_EVENT_WINDOW" if fully_adjusted
            else "MARKET_ADJUSTED_EVENT_WINDOW" if benchmark_metrics["benchmark_observations_matched"]
            else "UNADJUSTED_CLOSE_TO_CLOSE_EVENT_WINDOW"
        ),
        "causal_inference": False,
        "limitations": [
            "Market-adjusted return is descriptive and does not establish causation.",
            "Benchmark observations must match the asset's trading dates; unmatched dates are excluded.",
            "Corporate-action adjustment follows the source dataset methodology; demergers, rights, buybacks and mergers are not scaled.",
            "If adjusted bars are unavailable for any asset observation, raw close fallback is used.",
            "Corporate actions and market-wide confounders can affect the observed move.",
            "The event date is supplied by the researcher and is not independently verified.",
            "Only persisted EOD bars available to this account are included.",
        ],
    }
