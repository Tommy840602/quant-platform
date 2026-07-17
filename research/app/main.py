"""研究/AI 服務入口（FastAPI）。見架構文件 §8。"""
from fastapi import FastAPI
from app.api import factors, backtest, models

app = FastAPI(title="Quant Research/AI Service", version="1.0.0")

app.include_router(factors.router, prefix="/factors", tags=["factors"])
app.include_router(backtest.router, prefix="/backtest", tags=["backtest"])
app.include_router(models.router, prefix="/models", tags=["models"])


@app.get("/health")
def health():
    return {"status": "ok"}
