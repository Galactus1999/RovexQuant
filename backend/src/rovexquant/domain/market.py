from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from uuid import UUID

class DataQuality(StrEnum):
    VALID = "VALID"
    DELAYED = "DELAYED"
    STALE = "STALE"
    INCOMPLETE = "INCOMPLETE"
    GAP_DETECTED = "GAP_DETECTED"
    PROVIDER_DISCONNECTED = "PROVIDER_DISCONNECTED"
    ADJUSTMENT_PENDING = "ADJUSTMENT_PENDING"

@dataclass(frozen=True, slots=True)
class MarketBar:
    instrument_id: UUID
    timeframe: str
    start_at: datetime
    end_at: datetime
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int
    provider: str
    exchange_timestamp: datetime
    received_at: datetime
    is_complete: bool
    quality: DataQuality

    def valid_ohlc(self) -> bool:
        prices = (self.open, self.high, self.low, self.close)
        return (
            all(value.is_finite() and value > 0 for value in prices)
            and self.low <= min(self.open, self.close)
            and self.high >= max(self.open, self.close)
            and self.low <= self.high
            and isinstance(self.volume, int)
            and not isinstance(self.volume, bool)
            and self.volume >= 0
        )
