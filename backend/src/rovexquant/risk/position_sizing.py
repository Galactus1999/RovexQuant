from dataclasses import dataclass
from decimal import Decimal, ROUND_FLOOR

@dataclass(frozen=True, slots=True)
class RiskProfile:
    capital: Decimal
    risk_fraction: Decimal
    max_position_fraction: Decimal = Decimal("1.0")
    def __post_init__(self):
        if self.capital <= 0: raise ValueError("capital must be positive")
        if not Decimal("0") < self.risk_fraction <= Decimal("0.05"): raise ValueError("risk_fraction must be >0 and <=5%")
        if not Decimal("0") < self.max_position_fraction <= Decimal("1"): raise ValueError("max_position_fraction must be in (0,1]")

def position_quantity(profile: RiskProfile, entry: Decimal, stop: Decimal) -> int:
    if entry <= 0 or stop <= 0 or entry == stop: return 0
    risk_budget = profile.capital * profile.risk_fraction
    by_risk = (risk_budget / abs(entry-stop)).to_integral_value(rounding=ROUND_FLOOR)
    by_capital = (profile.capital * profile.max_position_fraction / entry).to_integral_value(rounding=ROUND_FLOOR)
    return max(0, int(min(by_risk, by_capital)))
