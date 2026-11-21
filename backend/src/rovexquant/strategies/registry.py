from rovexquant.strategies.base import Strategy
from rovexquant.strategies.trend import SmaCrossover, EmaTrend
from rovexquant.strategies.momentum import RsiRegime

class StrategyRegistry:
    def __init__(self): self._items: dict[str, Strategy] = {}
    def register(self, strategy: Strategy) -> None:
        key = strategy.metadata.strategy_id
        if key in self._items: raise ValueError(f"duplicate strategy: {key}")
        self._items[key] = strategy
    def get(self, strategy_id: str) -> Strategy: return self._items[strategy_id]
    def all(self) -> tuple[Strategy, ...]: return tuple(self._items[k] for k in sorted(self._items))

def default_registry() -> StrategyRegistry:
    registry = StrategyRegistry()
    for strategy in (SmaCrossover(), EmaTrend(), RsiRegime()): registry.register(strategy)
    return registry
