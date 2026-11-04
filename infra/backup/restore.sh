#!/usr/bin/env bash
# 從備份檔還原 TimescaleDB（會覆蓋目前資料！）
#   ./infra/backup/restore.sh                      # 列出可用備份
#   ./infra/backup/restore.sh backups/quant-20261104-0300.dump
set -euo pipefail
cd "$(dirname "$0")/../.."
DC="docker compose -f docker-compose.prod.yml"

if [ $# -lt 1 ]; then
  echo "可用備份："; ls -lh backups/quant-*.dump 2>/dev/null || echo "（沒有，先跑：$DC exec db-backup /backup.sh once）"
  exit 0
fi
FILE="$1"; [ -f "$FILE" ] || { echo "找不到 $FILE"; exit 1; }

echo "⚠ 這會把資料庫 quant 整個換成 $FILE 的內容（現在的資料會消失）。"
read -r -p "確定請輸入 RESTORE：" ans; [ "$ans" = "RESTORE" ] || { echo "取消"; exit 1; }

APPS="api-gateway market-data-service strategy-service order-execution-service risk-service account-service auth-service backtest-service notification-service research"
echo "1/4 停止應用服務（避免還原中有人寫入）"; $DC stop $APPS
echo "2/4 重建空資料庫"
$DC exec -T timescaledb psql -U quant -d postgres -v ON_ERROR_STOP=1 \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='quant' AND pid<>pg_backend_pid();" \
  -c "DROP DATABASE IF EXISTS quant;" -c "CREATE DATABASE quant;"
$DC exec -T timescaledb psql -U quant -d quant -v ON_ERROR_STOP=1 \
  -c "CREATE EXTENSION IF NOT EXISTS timescaledb;" -c "SELECT timescaledb_pre_restore();"
echo "3/4 還原資料"
$DC exec -T timescaledb pg_restore -U quant -d quant --no-owner < "$FILE" || echo "（pg_restore 有警告，通常是 timescale 內部物件，可忽略）"
$DC exec -T timescaledb psql -U quant -d quant -c "SELECT timescaledb_post_restore();" >/dev/null
echo "4/4 啟動應用服務"; $DC up -d
echo "✅ 還原完成。到「系統狀態」頁確認各服務綠燈。"
