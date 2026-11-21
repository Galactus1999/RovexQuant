from dataclasses import dataclass
from typing import Protocol, Sequence

@dataclass(frozen=True, slots=True)
class StrategyMetadata:
    strategy_id: str
    name: str
    version: str
    horizon: str
    description: str

class Strategy(Protocol):
    metadata: StrategyMetadata
    def evaluate(self, closes: Sequence[float]) -> str: ...
