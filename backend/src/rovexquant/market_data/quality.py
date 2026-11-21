from datetime import datetime, timedelta, timezone
from rovexquant.domain.market import DataQuality, MarketBar

BLOCKING = {DataQuality.STALE, DataQuality.INCOMPLETE, DataQuality.GAP_DETECTED, DataQuality.PROVIDER_DISCONNECTED, DataQuality.ADJUSTMENT_PENDING}

def validate_bar(bar: MarketBar, max_age: timedelta, now: datetime | None = None) -> tuple[bool, str]:
    now = now or datetime.now(timezone.utc)
    if not bar.is_complete: return False, "bar_incomplete"
    if not bar.valid_ohlc(): return False, "ohlc_invalid"
    if bar.quality in BLOCKING: return False, f"quality_{bar.quality.value.lower()}"
    if bar.received_at.tzinfo is None or bar.exchange_timestamp.tzinfo is None: return False, "timezone_missing"
    if now - bar.received_at > max_age: return False, "bar_stale"
    return True, "accepted"
