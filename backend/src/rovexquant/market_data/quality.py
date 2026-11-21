from datetime import datetime, timedelta, timezone
from rovexquant.domain.market import DataQuality, MarketBar

BLOCKING = {
    DataQuality.STALE,
    DataQuality.INCOMPLETE,
    DataQuality.GAP_DETECTED,
    DataQuality.PROVIDER_DISCONNECTED,
    DataQuality.ADJUSTMENT_PENDING,
}

def validate_bar(
    bar: MarketBar,
    max_age: timedelta,
    now: datetime | None = None,
    *,
    allow_delayed: bool = False,
    max_future_skew: timedelta = timedelta(seconds=5),
) -> tuple[bool, str]:
    now = now or datetime.now(timezone.utc)
    if max_age < timedelta(0) or max_future_skew < timedelta(0):
        return False, "invalid_time_policy"
    if now.tzinfo is None or now.utcoffset() is None:
        return False, "now_timezone_missing"
    if not bar.is_complete:
        return False, "bar_incomplete"
    if not bar.valid_ohlc():
        return False, "ohlc_invalid"
    if bar.quality in BLOCKING:
        return False, f"quality_{bar.quality.value.lower()}"
    if bar.quality == DataQuality.DELAYED and not allow_delayed:
        return False, "delayed_data_not_allowed"
    timestamps = (bar.received_at, bar.exchange_timestamp, bar.start_at, bar.end_at)
    if any(value.tzinfo is None or value.utcoffset() is None for value in timestamps):
        return False, "timezone_missing"
    if bar.start_at >= bar.end_at:
        return False, "bar_time_range_invalid"
    if bar.exchange_timestamp < bar.start_at - max_future_skew or bar.exchange_timestamp > bar.end_at + max_future_skew:
        return False, "exchange_timestamp_outside_bar"
    if bar.end_at > now + max_future_skew:
        return False, "future_bar"
    if bar.exchange_timestamp > bar.received_at + max_future_skew:
        return False, "exchange_timestamp_ahead_of_receipt"
    if bar.received_at > now + max_future_skew or bar.exchange_timestamp > now + max_future_skew:
        return False, "future_timestamp"
    if now - bar.received_at > max_age or now - bar.exchange_timestamp > max_age:
        return False, "bar_stale"
    return True, "accepted"
