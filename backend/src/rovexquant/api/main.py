from fastapi import FastAPI
from rovexquant.strategies.registry import default_registry

app = FastAPI(title="RovexQuant API", version="0.1.0", description="Research and paper trading; no order execution.")

@app.get("/api/v1/health")
def health(): return {"status": "ok", "application": "RovexQuant", "mode": "research-only"}

@app.get("/api/v1/strategies")
def strategies():
    return [{"strategy_id": s.metadata.strategy_id, "name": s.metadata.name, "horizon": s.metadata.horizon} for s in default_registry().all()]
