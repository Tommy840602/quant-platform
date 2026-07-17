#!/usr/bin/env bash
# 一鍵重啟：基礎設施(Docker) → 等待就緒 → 後端微服務 → 狀態。
# 用法：
#   ./restart-all.sh           完整重啟（infra + 後端全部）
#   ./restart-all.sh lite      後端不含 auth-service
#   ./restart-all.sh --no-infra 跳過 Docker，只重啟後端
set -uo pipefail
cd "$(dirname "$0")"
INFRA_DIR="../infra"

MODE="all"; SKIP_INFRA=0
for a in "$@"; do
  case "$a" in
    --no-infra) SKIP_INFRA=1 ;;
    core|lite|all) MODE="$a" ;;
  esac
done

echo "==> [1/5] 停止現有後端服務"
./stop-all.sh || true

# 等待某個 TCP 埠就緒（$1=名稱 $2=埠 $3=最多秒數）
wait_port() {
  local name="$1" port="$2" max="${3:-60}" i=0
  printf "   等待 %s (localhost:%s) " "$name" "$port"
  while ! nc -z localhost "$port" 2>/dev/null; do
    i=$((i+1)); if [ "$i" -ge "$max" ]; then echo " ✗ 逾時（$max 秒）"; return 1; fi
    printf "."; sleep 1
  done
  echo " ✓"
}

if [ "$SKIP_INFRA" -eq 0 ]; then
  echo "==> [2/5] 啟動基礎設施 (docker compose up -d)"
  if ! docker info >/dev/null 2>&1; then
    echo "   ✗ Docker 未執行。請先開 Docker Desktop（等狀態 running）再重跑。"; exit 1
  fi
  ( cd "$INFRA_DIR" && (docker compose up -d || docker-compose up -d) )

  echo "==> [3/5] 等待基礎設施就緒"
  wait_port "Postgres" 5433 90
  wait_port "Redis"    6379 60
  wait_port "Kafka"    9092 90
  wait_port "Nacos"    8848 120
  echo "   基礎設施就緒，緩衝 5 秒讓 Nacos 完成初始化..."; sleep 5
else
  echo "==> [2-3/5] 跳過基礎設施（--no-infra）"
fi

echo "==> [4/5] 啟動後端微服務 (${MODE})"
./start-all.sh "$MODE"

echo "==> [5/5] 服務狀態"
sleep 3
./status.sh || true

echo
echo "==> 完成。後端每支起來約需 1~2 分鐘，可用 ./status.sh 再確認。"
echo "    前端： cd ../frontend && npm run dev"
echo "    研究服務(選用)： cd ../research && uvicorn main:app --port 8000"
