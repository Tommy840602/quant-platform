#!/usr/bin/env bash
# 伺服器一鍵部署：拉最新碼 → 建置 → 啟動全部。
# 前置：VM 已裝 docker + docker compose，且已 cp .env.prod .env 並填好機密。
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE="docker compose -f docker-compose.prod.yml"

if [ ! -f .env ]; then
  echo "✗ 找不到 .env，請先： cp .env.prod .env 並填入金鑰"; exit 1
fi

echo "==> 拉取最新程式碼"
git pull --ff-only || echo "（略過 git pull）"

echo "==> 建置映像（首次較久，含 maven 建置全部服務）"
$COMPOSE build

echo "==> 啟動全部服務"
$COMPOSE up -d

echo "==> 服務狀態"
$COMPOSE ps

echo
echo "==> 完成。前端： http://<VM_IP>:${FRONTEND_PORT:-8088}"
echo "    查看日誌： $COMPOSE logs -f <service>"
echo "    後端起來約需 1~2 分鐘（等 Nacos 註冊）。"
