#!/usr/bin/env bash
# 開機冒煙測試：每支服務 jar 真的啟動一次（CI 提供 TimescaleDB + Redis；Nacos 關閉、Kafka 不在也要能起）。
# 抓「編譯過但開機炸」：缺依賴 / Bean 建構子歧義 / YAML 重複鍵 / Feign 缺 LoadBalancer / JPA 自動設定被排除…
set -u
cd "$(dirname "$0")/../.."
export DB_HOST=localhost DB_PORT=5432 REDIS_HOST=localhost KAFKA_ADDR=localhost:9092
export JWT_SECRET="ci-smoke-$(head -c 48 /dev/urandom | base64 | tr -d '\n')" JWT_ENABLED=true
export ADMIN_PASSWORD=ci-smoke-password-123 KLINE_HISTORY_ENABLED=false BINANCE_UDS_ENABLED=false
export TRADING_DRY_RUN=true BINANCE_ENV=testnet
COMMON="--spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.discovery.register-enabled=false --spring.cloud.nacos.config.enabled=false --spring.cloud.nacos.config.import-check.enabled=false"
fail=0
for jar in backend/*/target/*-1.0.0-SNAPSHOT.jar; do
  name=$(basename "$(dirname "$(dirname "$jar")")")
  [ "$name" = "common" ] && continue
  log="/tmp/boot-$name.log"
  echo "::group::boot $name"
  timeout 150 java -Xmx384m -jar "$jar" $COMMON > "$log" 2>&1 &
  pid=$!
  ok=0
  for i in $(seq 1 150); do
    if grep -q "Started .*Application" "$log"; then ok=1; break; fi
    if ! kill -0 $pid 2>/dev/null; then break; fi
    sleep 1
  done
  kill $pid 2>/dev/null; wait $pid 2>/dev/null
  if [ $ok = 1 ]; then echo "✅ $name started"; else
    echo "❌ $name did not start"; fail=1
    grep -E "APPLICATION FAILED|Application run failed|Exception|Caused by|duplicate key|Description:|Action:" -A 3 "$log" | head -60 | sed "s/^/::error::[$name] /"
  fi
  echo "::endgroup::"
done
exit $fail
