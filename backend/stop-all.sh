#!/usr/bin/env bash
# 停止所有由 start-all.sh 啟動的後端服務。
cd "$(dirname "$0")"
LOG_DIR="logs"
[[ -d "$LOG_DIR" ]] || { echo "無 logs 目錄，無服務在跑"; exit 0; }
for pidf in "$LOG_DIR"/*.pid; do
  [[ -e "$pidf" ]] || continue
  svc="$(basename "$pidf" .pid)"; pid="$(cat "$pidf")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "==> 停止 $svc (PID $pid)"; kill "$pid" 2>/dev/null || true
  fi
  rm -f "$pidf"
done
echo "==> 已全部停止。"
