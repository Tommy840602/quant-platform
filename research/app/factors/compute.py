"""因子計算（見架構文件 §8.1）。示範動能、波動率因子。"""
import numpy as np
import pandas as pd


def momentum(close: pd.Series, window: int = 20) -> pd.Series:
    """動能因子：過去 window 期報酬。"""
    return close.pct_change(window)


def volatility(close: pd.Series, window: int = 20) -> pd.Series:
    """波動率因子：過去 window 期報酬標準差（年化）。"""
    return close.pct_change().rolling(window).std() * np.sqrt(365)


def rsi(close: pd.Series, window: int = 14) -> pd.Series:
    """RSI 相對強弱指標。"""
    delta = close.diff()
    gain = delta.clip(lower=0).rolling(window).mean()
    loss = -delta.clip(upper=0).rolling(window).mean()
    rs = gain / (loss + 1e-9)
    return 100 - 100 / (1 + rs)


def information_coefficient(factor: pd.Series, fwd_return: pd.Series) -> float:
    """因子檢定：IC（因子值與未來報酬的相關性），見架構文件 §8.1。"""
    df = pd.concat([factor, fwd_return], axis=1).dropna()
    if len(df) < 2:
        return float("nan")
    return df.iloc[:, 0].corr(df.iloc[:, 1])
