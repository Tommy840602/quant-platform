# 部署指南（Hetzner 單機 docker-compose）

## 建議規格
- 完整版全套約佔 ~11GB RAM。若一台 VM 只跑本專案，**CX43（16GB）** 可；若同機還要跑其他 3 個專案，建議 **CX53（32GB）** 以上。
- Ubuntu 22.04/24.04，開放防火牆：22（SSH）、你的反代埠（80/443）。內部埠不對外。

## 一、伺服器初始化
```bash
# 安裝 docker + compose plugin
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # 重新登入生效

# 取得程式碼
git clone https://github.com/Tommy840602/quant-platform.git
cd quant-platform
```

## 二、設定機密
```bash
cp .env.prod .env
nano .env      # 填入金鑰；先維持 BINANCE_ENV=testnet、TRADING_DRY_RUN=true
```
> 幣安 API 金鑰的 **IP 白名單**填「這台 VM 的對外 IP」（`curl -4 ifconfig.me`）。因為請求從 VM 出去，白名單要放 VM 的 IP，不是你家的。

## 三、啟動
```bash
chmod +x deploy.sh
./deploy.sh
```
首次會用 maven 建置全部服務（較久）。之後改碼只要再 `./deploy.sh`。

- 前端： `http://<VM_IP>:8088`
- 後端起來約 1~2 分鐘（等 Nacos 註冊）。
- 查日誌： `docker compose -f docker-compose.prod.yml logs -f account-service`

## 四、綁網域（trade.tommy-huang.dev）
前端容器內部已把 `/api`、`/research` 反代到後端，對外只需一個埠（預設 8088）。用你 VM 上既有的反向代理指過去即可。

### Caddy 範例
```
trade.tommy-huang.dev {
    reverse_proxy localhost:8088
}
```

### Nginx Proxy Manager
新增 Proxy Host → Domain `trade.tommy-huang.dev` → Forward 到 `127.0.0.1:8088` → 開 SSL（Let's Encrypt）+ Websockets support。

### 原生 Nginx 範例
```nginx
server {
    server_name trade.tommy-huang.dev;
    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_buffering off;   # SSE
    }
    listen 443 ssl;  # certbot 補憑證
}
```

## 五、切正式網真錢（確認無誤後）
1. 幣安金鑰開「現貨交易」權限、IP 白名單填 VM IP、**不要開提領**。
2. `.env` 改 `BINANCE_ENV=mainnet`、填 `BINANCE_MAINNET_API_KEY/SECRET`。
3. `docker compose -f docker-compose.prod.yml up -d`，先維持 `TRADING_DRY_RUN=true` 確認 `/account` 讀到真實餘額。
4. 一切正常再把 `TRADING_DRY_RUN=false`（真送單），並先用小額。

## 監控（預設關閉，省記憶體）
Prometheus + Grafana 預設不啟動（放在 `monitoring` profile）。要看監控再開：
```bash
docker compose -f docker-compose.prod.yml --profile monitoring up -d
# Grafana： http://127.0.0.1:3000（僅本機，建議用 SSH tunnel 連）
```
關掉：
```bash
docker compose -f docker-compose.prod.yml --profile monitoring stop prometheus grafana
```
> CX43（16GB）建議平時關監控，把記憶體留給交易邏輯；要排查再臨時開。

## 在外面連回來（Cloudflare Tunnel）
家裡的 Mac 不用開任何對外埠、不用改路由器：`cloudflared` 容器主動連到 Cloudflare，只轉發**前端**（Grafana / Nacos / DB 都不會被連到）。
上公網前已補的保護：登入失敗鎖定（同帳號 15 分鐘錯 5 次、同 IP 錯 20 次 → 鎖 15 分鐘）、`/research` 也要登入、安全標頭。

### A. 臨時網址（今天馬上用，免帳號）
```bash
docker compose -f docker-compose.prod.yml --profile quick-tunnel up -d cloudflared-quick
docker compose -f docker-compose.prod.yml logs cloudflared-quick | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com'
```
- 打開印出來的網址，用平台帳號登入即可。網址隨機、**容器重啟就換**，用完建議關掉：
  `docker compose -f docker-compose.prod.yml stop cloudflared-quick`
- 只有平台自己的登入擋著 → 請確認 `ADMIN_PASSWORD` 夠長（16 字以上）、`JWT_ENABLED=true`。

### B. 固定網址 + 信箱驗證（推薦長期用）
1. Cloudflare 後台 → Zero Trust → Networks → Tunnels → Create tunnel（Cloudflared）→ 複製 token。
2. 該 tunnel 的 Public Hostname：`trade.你的網域` → Service `http://frontend:80`。
3. Zero Trust → Access → Applications → Self-hosted，網域同上；Policy：Allow，Include → Emails → 只填你自己的信箱（一次性驗證碼登入）。
4. `.env` 填 `CLOUDFLARE_TUNNEL_TOKEN=...`，然後：
   ```bash
   docker compose -f docker-compose.prod.yml --profile tunnel up -d cloudflared
   ```
這樣陌生人連登入頁都看不到（先過 Cloudflare 信箱驗證，再過平台登入）。

> 公司網路若擋 `stream.binance.com`，只會影響網頁上的即時報價跳動；下單、機器人都在家裡的 Mac 跑，不受影響。

## 常用指令
```bash
docker compose -f docker-compose.prod.yml ps           # 狀態
docker compose -f docker-compose.prod.yml logs -f X     # 日誌
docker compose -f docker-compose.prod.yml restart X     # 重啟某服務
docker compose -f docker-compose.prod.yml down          # 全停
```

> **Redis 現在是有狀態的**：冪等鍵、事件去重、策略現金帳、熔斷開關、回撤峰值都存在 Redis（`--appendonly yes`，掛載 `redis-data` volume）。
> 清空 Redis 等於清空熔斷狀態與現金帳；升級時請保留該 volume。
