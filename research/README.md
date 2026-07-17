# 研究 / AI 服務 (Python)

因子研究、特徵工程、模型訓練、向量化回測，以及對 Java 後端的整合端點（見架構文件 §8）。

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
# 文件: http://localhost:8000/docs
```

## 模組
- `app/factors/`   因子計算與因子庫
- `app/backtest/`  向量化回測（快速迭代，見架構文件 §7.5）
- `app/models/`    模型訓練與驗證（walk-forward）
- `app/api/`       FastAPI 路由，供後端以 REST/gRPC 呼叫
