from dataclasses import dataclass
from decimal import Decimal, ROUND_FLOOR
from typing import Literal

@dataclass(frozen=True, slots=True)
class RiskProfile:
    capital: Decimal
    risk_fraction: Decimal
    max_position_fraction: Decimal = Decimal("1.0")

    def __post_init__(self):
        if not self.capital.is_finite() or self.capital <= 0:
            raise ValueError("capital must be finite and positive")
        if not self.risk_fraction.is_finite() or not Decimal("0") < self.risk_fraction <= Decimal("0.05"):
            raise ValueError("risk_fraction must be finite, >0 and <=5%")
        if not self.max_position_fraction.is_finite() or not Decimal("0") < self.max_position_fraction <= Decimal("1"):
            raise ValueError("max_position_fraction must be finite and in (0,1]")

def position_quantity(
    profile: RiskProfile,
    entry: Decimal,
    stop: Decimal,
    *,
    side: Literal["long", "short"] = "long",
) -> int:
    if not entry.is_finite() or not stop.is_finite() or entry <= 0 or stop <= 0:
        return 0
    if side == "long" and stop >= entry:
        return 0
    if side == "short" and stop <= entry:
        return 0
    if side not in ("long", "short"):
        raise ValueError("side must be long or short")
    risk_budget = profile.capital * profile.risk_fraction
    risk_per_unit = abs(entry - stop)
    by_risk = (risk_budget / risk_per_unit).to_integral_value(rounding=ROUND_FLOOR)
    by_capital = (profile.capital * profile.max_position_fraction / entry).to_integral_value(rounding=ROUND_FLOOR)
    return max(0, int(min(by_risk, by_capital)))
