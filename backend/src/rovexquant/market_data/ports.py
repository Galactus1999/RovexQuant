from abc import ABC, abstractmethod
from datetime import datetime
from typing import Sequence
from rovexquant.domain.market import MarketBar

class MarketDataProvider(ABC):
    """Provider port; adapters must document entitlements and data limitations."""
    provider_id: str

    @abstractmethod
    async def get_bars(self, instrument_id: str, timeframe: str, start: datetime, end: datetime) -> Sequence[MarketBar]:
        raise NotImplementedError
