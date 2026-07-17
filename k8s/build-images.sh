#!/usr/bin/env bash
# 於 backend/ 先 mvn clean install -DskipTests，再建置各服務映像。
set -euo pipefail
cd "$(dirname "$0")/../backend"
VER=1.0.0-SNAPSHOT
REGISTRY="${REGISTRY:-quant}"   # 可設為你的 registry 前綴
SERVICES=(api-gateway auth-service market-data-service strategy-service \
  order-execution-service risk-service account-service backtest-service)
for s in "${SERVICES[@]}"; do
  echo "==> build $REGISTRY/$s:$VER"
  docker build -t "$REGISTRY/$s:$VER" --build-arg JAR="$s/target/$s-$VER.jar" -f Dockerfile .
done
echo "完成。若用遠端 registry 請 docker push。"
