"""因子 API：供後端請求因子計算/檢定。"""
from fastapi import APIRouter
from pydantic import BaseModel
import pandas as pd

from app.factors import compute

router = APIRouter()


class SeriesIn(BaseModel):
    close: list[float]
    window: int = 20


@router.post("/momentum")
def momentum(body: SeriesIn):
    s = pd.Series(body.close)
    return {"momentum": compute.momentum(s, body.window).fillna(0).tolist()}


@router.post("/rsi")
def rsi(body: SeriesIn):
    s = pd.Series(body.close)
    return {"rsi": compute.rsi(s, body.window).fillna(50).tolist()}
