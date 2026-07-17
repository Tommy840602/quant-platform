"""
向量化回測（見架構文件 §7.5）：快速迭代因子/參數。
高保真、與實盤一致的事件驅動回測由 Java 回測層負責。
"""
import numpy as np
import pandas as pd


def run(prices: pd.Series, signal: pd.Series,
        fee: float = 0.0004, slippage: float = 0.0002) -> dict:
    """
    signal: 目標倉位（-1..1），對齊 prices 索引。
    回傳績效指標與權益曲線（見架構文件 §7.3）。
    """
    ret = prices.pct_change().fillna(0)
    pos = signal.shift(1).fillna(0)              # 避免未來函數
    turnover = pos.diff().abs().fillna(0)
    cost = turnover * (fee + slippage)
    strat_ret = pos * ret - cost
    equity = (1 + strat_ret).cumprod()
    drawdown = equity / equity.cummax() - 1

    ann = (equity.iloc[-1]) ** (365 / max(len(equity), 1)) - 1
    sharpe = np.sqrt(365) * strat_ret.mean() / (strat_ret.std() + 1e-9)
    dd = drawdown.min()
    win = (strat_ret[strat_ret != 0] > 0).mean()

    return {
        "metrics": {
            "annual_return": round(float(ann), 4),
            "sharpe": round(float(sharpe), 3),
            "max_drawdown": round(float(dd), 4),
            "win_rate": round(float(win), 4) if not np.isnan(win) else 0.0,
            "final_equity": round(float(equity.iloc[-1]), 4),
        },
        "equity": [round(float(x), 4) for x in equity.tolist()],
        "drawdown": [round(float(x), 4) for x in drawdown.tolist()],
    }
