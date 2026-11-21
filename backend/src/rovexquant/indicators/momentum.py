from collections.abc import Sequence

def rsi(closes: Sequence[float], period: int = 14) -> list[float | None]:
    if period <= 0: raise ValueError("period must be positive")
    out: list[float | None] = [None] * len(closes)
    if len(closes) <= period: return out
    changes = [closes[i]-closes[i-1] for i in range(1, len(closes))]
    gains = [max(x, 0.0) for x in changes]
    losses = [max(-x, 0.0) for x in changes]
    avg_gain, avg_loss = sum(gains[:period])/period, sum(losses[:period])/period
    def calc(g: float, l: float) -> float:
        if l == 0: return 100.0 if g > 0 else 50.0
        return 100.0 - 100.0/(1.0+g/l)
    out[period] = calc(avg_gain, avg_loss)
    for i in range(period+1, len(closes)):
        j = i-1
        avg_gain = ((period-1)*avg_gain + gains[j])/period
        avg_loss = ((period-1)*avg_loss + losses[j])/period
        out[i] = calc(avg_gain, avg_loss)
    return out
