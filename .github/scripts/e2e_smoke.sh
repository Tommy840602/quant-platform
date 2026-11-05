#!/usr/bin/env bash
# E2E 冒煙（後續 8）：真的起 risk / account / order-execution 三支（接 CI 的 TimescaleDB + Redis + Kafka，Nacos 關、
# Feign 走靜態實例表），下一張 Dry-Run 市價單 → 風控核可 → 本機撮合 FILLED → Kafka → account-service 部位更新；
# 再掛一張未觸價限價單，推價格觸價 → 3 秒排程撮合成交。改 OMS / 風控 / 帳務時最有保障的一條鏈。
set -u
cd "$(dirname "$0")/../.."
export DB_HOST=localhost DB_PORT=5432 REDIS_HOST=localhost KAFKA_ADDR=localhost:9092
export JWT_SECRET="ci-e2e-$(head -c 48 /dev/urandom | base64 | tr -d '\n')" JWT_ENABLED=true
export TRADING_DRY_RUN=true BINANCE_ENV=testnet KLINE_HISTORY_ENABLED=false BINANCE_UDS_ENABLED=false
export RISK_MAX_NOTIONAL=1000 RISK_FAIL_CLOSED=false
COMMON="--spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.discovery.register-enabled=false --spring.cloud.nacos.config.enabled=false --spring.cloud.nacos.config.import-check.enabled=false"
# Feign 靜態實例（沒有 Nacos）
DISC="--spring.cloud.discovery.client.simple.instances.risk-service[0].uri=http://localhost:8085 \
--spring.cloud.discovery.client.simple.instances.account-service[0].uri=http://localhost:8096 \
--spring.cloud.discovery.client.simple.instances.order-execution-service[0].uri=http://localhost:8084 \
--spring.cloud.discovery.client.simple.instances.notification-service[0].uri=http://localhost:8098"

jar() { ls backend/$1/target/*-1.0.0-SNAPSHOT.jar | head -1; }
start() { # name
  local log="/tmp/e2e-$1.log"
  java -Xmx384m -jar "$(jar $1)" $COMMON $DISC > "$log" 2>&1 &
  echo $! > "/tmp/e2e-$1.pid"
}
wait_started() { # name secs
  for i in $(seq 1 $2); do grep -q "Started .*Application" "/tmp/e2e-$1.log" && return 0; sleep 1; done
  echo "::error::$1 沒在 $2 秒內啟動"; grep -E "APPLICATION FAILED|Caused by" -A 3 "/tmp/e2e-$1.log" | head -30; return 1
}
cleanup() { for n in risk-service account-service order-execution-service; do kill "$(cat /tmp/e2e-$n.pid 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT

start risk-service; start account-service; start order-execution-service
wait_started risk-service 150 || exit 1
wait_started account-service 150 || exit 1
wait_started order-execution-service 150 || exit 1
sleep 5   # Kafka listener 分區分配

redis-cli SET price:BTCUSDT 60000 >/dev/null
post() { curl -s -X POST "$1" -H 'Content-Type: application/json' -d "$2"; }

echo "::group::1) Dry-Run 市價單 → FILLED → 部位"
r=$(post http://localhost:8084/api/order/orders '{"clientOrderId":"e2e-mkt-1","strategyId":"e2e","symbol":"BTCUSDT","side":"BUY","type":"MARKET","quantity":0.001,"reduceOnly":false,"market":"SPOT","account":"main"}')
echo "submit: $r"
echo "$r" | grep -q '"code":0' || { echo "::error::下單失敗: $r"; exit 1; }
ok=0
for i in $(seq 1 60); do
  p=$(curl -s "http://localhost:8096/api/account/positions?account=main")
  if echo "$p" | grep -q '"symbol":"BTCUSDT"' && echo "$p" | grep -Eq '"quantity":(0\.001|1\.0E-3)'; then ok=1; break; fi
  sleep 1
done
echo "positions: $p"
[ $ok = 1 ] || { echo "::error::60 秒內 account-service 沒收到成交（Kafka → 部位這條鏈斷了）"; grep -iE "error|exception" /tmp/e2e-account-service.log | tail -5; exit 1; }
status_of() { curl -s "http://localhost:8084/api/order/orders?limit=20" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(next((r['status'] for r in d if r['clientOrderId']=='$1'),'MISSING'))"; }
st=$(status_of e2e-mkt-1); echo "oms status: $st"
[ "$st" = "FILLED" ] || { echo "::error::OMS 訂單狀態不是 FILLED: $st"; exit 1; }
fq=$(curl -s "http://localhost:8084/api/order/orders?limit=20" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(next((r.get('filledQty') for r in d if r['clientOrderId']=='e2e-mkt-1'),'MISSING'))")
echo "oms filledQty: $fq"
python3 -c "import sys; sys.exit(0 if abs(float('$fq')-0.001)<1e-12 else 1)" || { echo "::error::OMS 成交量 $fq ≠ 0.001（Dry-Run 重複入帳）"; exit 1; }
echo "✅ 市價單成交並更新部位（OMS 成交量沒有重複）"
echo "::endgroup::"

echo "::group::2) 限價單未觸價掛著 → 觸價成交"
r=$(post http://localhost:8084/api/order/orders '{"clientOrderId":"e2e-lim-1","strategyId":"e2e","symbol":"BTCUSDT","side":"BUY","type":"LIMIT","price":59000,"quantity":0.001,"reduceOnly":false,"market":"SPOT","account":"main"}')
echo "submit: $r"; echo "$r" | grep -q '"code":0' || { echo "::error::限價單下單失敗: $r"; exit 1; }
sleep 6
st=$(status_of e2e-lim-1); echo "before touch: $st"
[ "$st" = "SUBMITTED" ] || { echo "::error::未觸價的限價單應該是 SUBMITTED，實際 $st"; exit 1; }
redis-cli SET price:BTCUSDT 58900 >/dev/null
ok=0
for i in $(seq 1 20); do
  p=$(curl -s "http://localhost:8096/api/account/positions?account=main")
  if echo "$p" | grep -Eq '"quantity":(0\.002|2\.0E-3)'; then ok=1; break; fi
  sleep 1
done
echo "positions: $p"
[ $ok = 1 ] || { echo "::error::觸價後 20 秒內限價單沒成交 / 部位沒累加"; exit 1; }
echo "✅ 限價單觸價成交，部位 0.001 → 0.002"
echo "::endgroup::"

echo "::group::3) 風控拒單（熔斷）會擋在送單前"
curl -s -X POST "http://localhost:8085/api/risk/kill-switch?on=true&reason=e2e" >/dev/null
r=$(post http://localhost:8084/api/order/orders '{"clientOrderId":"e2e-ks-1","strategyId":"e2e","symbol":"BTCUSDT","side":"BUY","type":"MARKET","quantity":0.001,"reduceOnly":false,"market":"SPOT","account":"main"}')
echo "submit under kill-switch: $r"
echo "$r" | grep -q '"code":403' || { echo "::error::熔斷中仍可下單: $r"; exit 1; }
curl -s -X POST "http://localhost:8085/api/risk/kill-switch?on=false&reason=e2e" >/dev/null
echo "✅ 熔斷擋單"
echo "::endgroup::"
echo "E2E OK"
