"""向量化回測 API（見架構文件 §7.5）。"""
from fastapi import APIRouter
from pydantic import BaseModel
import pandas as pd

from app.backtest import vectorized

router = APIRouter()


class BacktestIn(BaseModel):
    prices: list[float]
    signal: list[float]      # 目標倉位 -1..1
    fee: float = 0.0004
    slippage: float = 0.0002


@router.post("/run")
def run(body: BacktestIn):
    prices = pd.Series(body.prices)
    signal = pd.Series(body.signal)
    return vectorized.run(prices, signal, body.fee, body.slippage)
