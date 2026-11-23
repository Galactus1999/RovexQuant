"""Deterministic event-window study over point-in-time EOD bars.

This reports observed price behaviour only. Without a matching benchmark series it
does not claim abnormal returns or causal event impact.
"""
from __future__ import annotations

from datetime import date
from math import log, sqrt
from statistics import pstdev
from typing import Any


def calculate_event_window(
    bars: list[dict[str, Any]], *, event_date: date, pre_bars: int = 10, post_bars: int = 10
) -> dict[str, Any]:
    if not 1 <= pre_bars <= 60 or not 1 <= post_bars <= 60:
        raise ValueError("Event windows must be between 1 and 60 trading bars")
    ordered = sorted(
        (bar for bar in bars if isinstance(bar.get("date"), str) and isinstance(bar.get("close"), (int, float))),
        key=lambda bar: bar["date"],
    )
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
    returns = [float(bar["close"]) / anchor_close - 1.0 for bar in window]
    daily_returns = [
        float(window[i]["close"]) / float(window[i - 1]["close"]) - 1.0
        for i in range(1, len(window))
    ]
    peaks: list[float] = []
    peak = anchor_close
    drawdown = 0.0
    for bar in window:
        close = float(bar["close"])
        peak = max(peak, close)
        drawdown = min(drawdown, close / peak - 1.0)
        peaks.append(peak)
    return {
        "event_date": event_date.isoformat(),
        "anchor_date": anchor["date"],
        "anchor_close": anchor_close,
        "first_post_event_date": window[0]["date"],
        "last_post_event_date": window[-1]["date"],
        "pre_event_bars_available": min(pre_bars, len(before)),
        "post_event_bars_used": len(window),
        "cumulative_return": returns[-1],
        "best_close_return": max(returns),
        "worst_close_return": min(returns),
        "post_window_max_drawdown": drawdown,
        "realized_daily_volatility": pstdev(daily_returns) * sqrt(252) if len(daily_returns) > 1 else None,
        "method": "UNADJUSTED_CLOSE_TO_CLOSE_EVENT_WINDOW",
        "causal_inference": False,
        "abnormal_return": None,
        "limitations": [
            "No benchmark-adjusted abnormal return is computed.",
            "Corporate actions and market-wide confounders can affect the observed move.",
            "The event date is supplied by the researcher and is not independently verified.",
            "Only persisted EOD bars available to this account are included.",
        ],
    }
