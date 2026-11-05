#!/usr/bin/env bash
# 開機冒煙測試：每支服務 jar 真的啟動一次（CI 提供 TimescaleDB + Redis；Nacos 關閉、Kafka 不在也要能起）。
# 抓「編譯過但開機炸」：缺依賴 / Bean 建構子歧義 / YAML 重複鍵 / Feign 缺 LoadBalancer / JPA 自動設定被排除…
set -u
cd "$(dirname "$0")/../.."
export DB_HOST=localhost DB_PORT=5432 REDIS_HOST=localhost KAFKA_ADDR=localhost:9092
export JWT_SECRET="ci-smoke-$(head -c 48 /dev/urandom | base64 | tr -d '\n')" JWT_ENABLED=true
export ADMIN_PASSWORD=ci-smoke-password-123 KLINE_HISTORY_ENABLED=false BINANCE_UDS_ENABLED=false
export TRADING_DRY_RUN=true BINANCE_ENV=testnet
export MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true   # 與 compose 相同：容器健康檢查打 /actuator/health/liveness
COMMON="--spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.discovery.register-enabled=false --spring.cloud.nacos.config.enabled=false --spring.cloud.nacos.config.import-check.enabled=false"
fail=0
for jar in backend/*/target/*-1.0.0-SNAPSHOT.jar; do
  name=$(basename "$(dirname "$(dirname "$jar")")")
  [ "$name" = "common" ] && continue
  log="/tmp/boot-$name.log"
  echo "::group::boot $name"
  # 跟 docker-compose.prod.yml 一樣的 heap / JVM 旗標：開機就 OOM / Metaspace 不夠會在這裡抓到
  case "$name" in
    auth-service|notification-service) heap=160m ;; risk-service) heap=192m ;;
    market-data-service) heap=320m ;; strategy-service|backtest-service) heap=384m ;; *) heap=256m ;;
  esac
  timeout 150 java -Xmx$heap -Xms64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=160m \
    -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -Xss512k -jar "$jar" $COMMON > "$log" 2>&1 &
  pid=$!
  ok=0
  for i in $(seq 1 150); do
    if grep -q "Started .*Application" "$log"; then ok=1; break; fi
    if ! kill -0 $pid 2>/dev/null; then break; fi
    sleep 1
  done
  if [ $ok = 1 ]; then
    # compose 的健康檢查打 liveness：確認這個端點真的在（回 200）
    port=$(grep -oE "(Tomcat|Netty) started on port[s]?[^0-9]*[0-9]+" "$log" | grep -oE "[0-9]+$" | head -1)
    ctx=$(grep -oE "context path '[^']*'" "$log" | head -1 | sed "s/context path '//; s/'$//")
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}${ctx}/actuator/health/liveness")
    if [ "$code" != 200 ]; then echo "::error::[$name] liveness http://127.0.0.1:${port}${ctx}/actuator/health/liveness → $code"; ok=0; fi
  fi
  kill $pid 2>/dev/null; wait $pid 2>/dev/null
  if [ $ok = 1 ]; then echo "✅ $name started (liveness 200)"; else
    echo "❌ $name did not start"; fail=1
    grep -E "APPLICATION FAILED|Application run failed|Exception|Caused by|duplicate key|Description:|Action:" -A 3 "$log" | head -60 | sed "s/^/::error::[$name] /"
  fi
  echo "::endgroup::"
done
exit $fail
