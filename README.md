# 量化交易平台 Quant Trading Platform

研究 → 回測 → 實盤交易 全流程量化交易平台專案骨架。

## 技術棧
- **前端**：Vue 3 (JavaScript) + Vite + Pinia + Vue Router + Element Plus + ECharts / TradingView Lightweight Charts
- **後端**：Java 17 + Spring Boot 3.x + Spring Cloud（Nacos / Gateway / OpenFeign / Resilience4j）
- **研究/AI**：Python 3 + FastAPI + pandas + scikit-learn
- **資料**：PostgreSQL / TimescaleDB、Redis、Apache Kafka
- **交易所**：Binance（現貨 Spot + USDT-M 永續）

## 目錄結構
```
quant-platform/
├── infra/                  # 基礎設施：docker-compose、初始化腳本
├── backend/                # Spring Cloud 微服務（Maven 多模組）
│   ├── common/             # 共用 DTO / 事件 / 工具
│   ├── api-gateway/        # Spring Cloud Gateway
│   ├── auth-service/       # 認證授權、JWT、API Key 保管
│   ├── market-data-service/# 幣安行情訂閱、K線、Kafka 分發（含 WS 範例）
│   ├── strategy-service/   # 策略註冊、訊號、下單意圖
│   ├── order-execution-service/ # OMS：下單、狀態機、冪等
│   ├── risk-service/       # 三道防線風控
│   └── account-service/    # 帳戶、部位、對帳
├── frontend/               # Vue 3 SPA
└── research/               # Python 研究 / AI 服務（FastAPI）
```

## 快速開始
### 1. 啟動基礎設施
```bash
cd infra
docker compose up -d          # 啟動 Postgres/TimescaleDB, Redis, Kafka, Nacos
# Nacos 控制台: http://localhost:8848/nacos  (nacos / nacos)
```

### 2. 啟動後端
```bash
cd backend
mvn clean install -DskipTests
# 依序啟動各服務（或用 IDE）
mvn -pl api-gateway spring-boot:run
mvn -pl market-data-service spring-boot:run
# ... 其餘服務同理
```

### 3. 啟動前端
```bash
cd frontend
npm install
npm run dev                   # http://localhost:5173
```

### 4. 啟動研究服務
```bash
cd research
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

## 環境切換（testnet / mainnet）
Binance 端點與金鑰由設定中心（Nacos）或環境變數注入，預設使用 **testnet**。
切換至 mainnet 前，務必於 testnet 完整驗證，並確認 `trading.dry-run=false`。

> ⚠️ 本專案為**骨架**：核心鏈路（gateway、market-data WS、下單狀態機）提供可運行範例，
> 其餘服務為結構化 scaffold，標註 `TODO` 供逐步實作。切勿在未經風控驗證下直接連線 mainnet 交易。
