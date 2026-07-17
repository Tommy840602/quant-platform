"""模型訓練 API。"""
from fastapi import APIRouter
from pydantic import BaseModel
import numpy as np

from app.models import train

router = APIRouter()


class TrainIn(BaseModel):
    X: list[list[float]]
    y: list[int]
    n_splits: int = 5


@router.post("/train")
def train_model(body: TrainIn):
    res = train.walk_forward_train(np.array(body.X), np.array(body.y), body.n_splits)
    return {"cv_scores": res.cv_scores, "mean_score": res.mean_score}
