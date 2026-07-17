"""
模型訓練與驗證（見架構文件 §8.2）。
以 walk-forward + 時間序切分防止洩漏；實務可接 MLflow 追蹤。
"""
from dataclasses import dataclass

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import TimeSeriesSplit
from sklearn.metrics import accuracy_score


@dataclass
class TrainResult:
    cv_scores: list
    mean_score: float


def walk_forward_train(X: np.ndarray, y: np.ndarray, n_splits: int = 5) -> TrainResult:
    """時間序 walk-forward 交叉驗證，回傳各折分數。"""
    tscv = TimeSeriesSplit(n_splits=n_splits)
    scores = []
    for tr, te in tscv.split(X):
        model = GradientBoostingClassifier()
        model.fit(X[tr], y[tr])
        scores.append(accuracy_score(y[te], model.predict(X[te])))
    return TrainResult(cv_scores=scores, mean_score=float(np.mean(scores)))
    # TODO: mlflow.log_metrics(...); 打包模型為策略工件（見架構文件 §8.1）
