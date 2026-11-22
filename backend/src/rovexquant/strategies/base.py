from dataclasses import dataclass
from typing import Protocol

@dataclass(frozen=True, slots=True)
class StrategyMetadata:
    strategy_id: str
    name: str
    version: str
    horizon: str
    description: str

class Strategy(Protocol):
    """Common discovery contract. Evaluation inputs are strategy-specific."""
    metadata: StrategyMetadata
