#!/usr/bin/env bash
# 背景啟動所有後端服務（讀取 mvn install 產生的可執行 jar）。
# 用法：
#   ./start-all.sh          啟動全部
#   ./start-all.sh core     只啟動 api-gateway + market-data-service
set -euo pipefail
cd "$(dirname "$0")"

# load .env（若存在），將變數匯出給各服務
if [ -f .env ]; then set -a; . ./.env; set +a; echo "==> 已載入 .env"; fi

LOG_DIR="logs"; mkdir -p "$LOG_DIR"
VERSION="1.0.0-SNAPSHOT"
JAVA_OPTS="${JAVA_OPTS:--Xms96m -Xmx320m -XX:+UseSerialGC -Djava.net.preferIPv4Stack=true}"

# 服務啟動順序（gateway 先，資料相依服務隨後）
ALL=(api-gateway market-data-service strategy-service order-execution-service risk-service account-service auth-service backtest-service notification-service)
CORE=(api-gateway market-data-service)
LITE=(api-gateway market-data-service strategy-service order-execution-service risk-service account-service backtest-service notification-service)

case "${1:-all}" in
  core) SERVICES=("${CORE[@]}") ;;
  lite) SERVICES=("${LITE[@]}") ;;
  *)    SERVICES=("${ALL[@]}") ;;
esac

echo "==> 檢查基礎設施 (Nacos 8848 / Kafka 9092 / Redis 6379 / Postgres 5433)"
nc -z localhost 8848 2>/dev/null && echo "   Nacos OK" || echo "   ⚠️ Nacos 未就緒，服務可能註冊失敗（先 docker compose up -d）"

for svc in "${SERVICES[@]}"; do
  JAR="$svc/target/$svc-$VERSION.jar"
  if [[ ! -f "$JAR" ]]; then
    echo "==> [$svc] 找不到 jar，請先在 backend 執行： mvn clean install -DskipTests"
    continue
  fi
  if [[ -f "$LOG_DIR/$svc.pid" ]] && kill -0 "$(cat "$LOG_DIR/$svc.pid")" 2>/dev/null; then
    echo "==> [$svc] 已在執行 (PID $(cat "$LOG_DIR/$svc.pid"))，略過"
    continue
  fi
  echo "==> 啟動 $svc ..."
  nohup java $JAVA_OPTS -jar "$JAR" > "$LOG_DIR/$svc.log" 2>&1 &
  echo $! > "$LOG_DIR/$svc.pid"
  sleep 4   # 稍微錯開，讓 gateway/相依先起
done

echo ""
echo "==> 完成。查看日誌： tail -f logs/<服務>.log"
echo "==> 查看狀態： ./status.sh    停止： ./stop-all.sh"
