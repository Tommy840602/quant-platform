# 量化交易平台 Quant Trading Platform

一套可實際運行的全流程量化交易系統：**即時看盤 → 技術分析 → 策略/機器人 → 回測 → 實盤自動交易 → 風控 → 通知**，串接幣安（Binance）現貨與 USDT-M 永續，已在幣安正式網完成真錢下單閉環驗證。

> ⚠️ **真錢風險**：本系統可連線幣安正式網以真實資金下單。請先在 testnet / dry-run 充分驗證，理解每一項風控設定後再上真錢，且務必從小額開始。作者不對任何交易損失負責。

## 特色

- **三位一體策略**：30 種策略（均線/MACD/布林/KDJ/RSI/SuperTrend/唐奇安/一目均衡…）可同時作為**看盤指標 + 交易機器人 + 回測**。
- **20 個交易對**、即時行情(幣安公開串流)、TradingView 風格 K 線 + 買賣點標註、日/夜主題。
- **實盤下單閉環**：冪等 → 事中風控 → 交易所 filter 對齊(stepSize/tickSize/minNotional) → 簽名下單(HMAC) → 成交回報 → 部位/損益落地。
- **按名目金額下單**：策略每單以目標 USDT 換算各幣數量，便宜幣也不會被 minQty/minNotional 擋。
- **風控**：全域熔斷(kill switch)、單筆名目上限、下單前餘額檢查、回撤熔斷；**風控頁可即時調整,免重啟**。
- **多環境切換**：登入頁一鍵切 testnet / mainnet、輸入金鑰、切 dry-run；雙鎖保護真錢。
- **回測**：Java 事件驅動(與實盤共用策略介面) + Python 向量化 + 參數網格最佳化 + 組合權重。
- **Slack 通知**：成交、下單被拒(含原因)、策略上下線、回撤預警/熔斷、每 6 小時淨值摘要(含當日成交/盈虧)。
- **監控**：Prometheus + Grafana(預設關閉、`--profile monitoring` 開啟)。

## 技術棧

- **前端**：Vue 3 (JavaScript) + Vite + Pinia + Vue Router + Element Plus + ECharts + Lightweight Charts
- **後端**：Java 17 + Spring Boot 3.x + Spring Cloud（Nacos 註冊/設定、Gateway、OpenFeign、Resilience4j）
- **研究/AI**：Python 3 + FastAPI + pandas + numpy + scikit-learn
- **資料**：TimescaleDB(PostgreSQL)、Redis、Apache Kafka(KRaft)
- **交易所**：Binance（現貨 Spot + USDT-M 永續）

## 微服務

| 服務 | 埠 | 職責 |
|---|---|---|
| api-gateway | 8080 | 統一入口、路由、JWT 驗證 |
| auth-service | 8081 | 登入、JWT 簽發 |
| market-data-service | 8082 | 幣安行情訂閱、K 線、Kafka 分發 |
| strategy-service | 8083 | 30 策略 + 機器人、按名目下單、多策略並行 |
| order-execution-service | 8084 | OMS：冪等、filter 對齊、下單/查/撤、成交回報、風控補強 |
| risk-service | 8085 | 事中核可、全域熔斷 |
| backtest-service | 8087 | Java 事件驅動回測、參數最佳化、一致性報告 |
| account-service | 8096 | 真實帳戶餘額(TWD 換算)、部位、資金曲線、回撤熔斷、每日摘要 |
| notification-service | 8098 | Slack 通知 |

## 目錄結構

```
quant-platform/
├── infra/                     # docker-compose(基礎設施) + init/prometheus/grafana
├── backend/                   # Spring Cloud 多模組(Maven)
│   ├── common/                # 共用 DTO / 事件 / 30 策略
│   ├── api-gateway/ auth-service/ market-data-service/ strategy-service/
│   ├── order-execution-service/ risk-service/ backtest-service/ account-service/ notification-service/
│   ├── start-all.sh stop-all.sh status.sh restart-all.sh   # 本機啟停腳本
│   └── .env.example           # 環境變數範本(金鑰請自填，勿提交)
├── frontend/                  # Vue 3 SPA（含 Dockerfile + nginx.conf）
├── research/                  # Python FastAPI 研究/回測（含 Dockerfile）
├── docker-compose.prod.yml    # 正式部署（單機全套）
├── deploy.sh  DEPLOY.md       # 一鍵部署腳本 + 指南
└── k8s/                       # Kubernetes manifests
```

## 本機快速開始

```bash
# 1) 基礎設施
cd infra && docker compose up -d          # Postgres/Redis/Kafka/Nacos

# 2) 後端（建置 + 背景啟動全部）
cd ../backend
cp .env.example .env                      # 填入幣安金鑰等（預設 testnet、dry-run）
mvn clean install -DskipTests
./restart-all.sh                          # 等 infra 就緒後啟動 9 服務

# 3) 前端
cd ../frontend && npm install && npm run dev     # http://localhost:5173

# 4) 研究服務（選用）
cd ../research && pip install -r requirements.txt && uvicorn app.main:app --port 8000
```

## 設定（環境變數 / .env）

| 變數 | 說明 | 預設 |
|---|---|---|
| `BINANCE_ENV` | `testnet` / `mainnet` | testnet |
| `TRADING_DRY_RUN` | `true`=乾跑不送單 | true |
| `BINANCE_API_KEY` / `_SECRET` | testnet 金鑰 | 空 |
| `BINANCE_MAINNET_API_KEY` / `_SECRET` | 正式網金鑰 | 空 |
| `ORDER_NOTIONAL_USDT` | 策略每單目標名目(USDT)，0=固定數量 | 0 |
| `RISK_MAX_NOTIONAL` | 單筆名目上限(USDT) | 1000 |
| `RISK_BALANCE_CHECK` | BUY 前查餘額 | false |
| `SLACK_WEBHOOK_URL` | Slack Incoming Webhook | 空 |

> 這些也可在前端「連線設定」與「風控」頁**執行期調整、即時生效**；`.env` 為開機預設值。

## 上真錢前檢查清單

1. 幣安 API 金鑰：開「現貨交易」權限、**設 IP 白名單**(填伺服器對外 IP)、**不要開提現**。
2. 若啟用「交易幣對白名單」，需把要交易的 symbol 全部加入。
3. 後端強制 IPv4（`start-all.sh` 已含 `-Djava.net.preferIPv4Stack=true`）。
4. 先 `RISK_MAX_NOTIONAL` 設小額、`RISK_BALANCE_CHECK=true`，小額實測買/賣各一張確認閉環。
5. 確認 `BINANCE_ENV=mainnet` 且 `TRADING_DRY_RUN=false` 才會真下單（雙鎖）。

## 部署

單機 docker-compose 全套（含前端 nginx 反代 /api、/research）。詳見 [`DEPLOY.md`](DEPLOY.md)：

```bash
cp .env.prod .env && nano .env            # 填金鑰（IP 白名單填本機對外 IP）
./deploy.sh                               # build + up
# 監控（預設關）： docker compose -f docker-compose.prod.yml --profile monitoring up -d
```

## 授權

個人專案，僅供學習與研究。使用者需自行承擔一切交易風險。
