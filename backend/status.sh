#!/usr/bin/env bash
# 顯示各後端服務狀態（相容 macOS 內建 bash 3.2）。
cd "$(dirname "$0")"
LOG_DIR="logs"
SERVICES="api-gateway:8080 auth-service:8081 market-data-service:8082 strategy-service:8083 order-execution-service:8084 risk-service:8085 account-service:8096 backtest-service:8087 notification-service:8098"
printf "%-26s %-6s %-8s %s\n" "服務" "埠" "PID" "狀態"
for entry in $SERVICES; do
  svc="${entry%%:*}"; port="${entry##*:}"
  pidf="$LOG_DIR/$svc.pid"; st="stopped"; pid="-"
  if [ -f "$pidf" ] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
    pid="$(cat "$pidf")"; st="running"
  fi
  printf "%-26s %-6s %-8s %s\n" "$svc" "$port" "$pid" "$st"
done
