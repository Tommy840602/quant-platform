#!/bin/sh
# TimescaleDB 每日備份：每天 BACKUP_HOUR 點（台北時間）pg_dump -Fc 到 /backups，保留 BACKUP_KEEP_DAYS 天。
#   立刻備份一次：docker compose -f docker-compose.prod.yml exec db-backup /backup.sh once
set -eu
: "${PGHOST:=timescaledb}" "${PGUSER:=quant}" "${PGDATABASE:=quant}"
: "${BACKUP_HOUR:=3}" "${BACKUP_KEEP_DAYS:=7}"
export PGHOST PGUSER PGDATABASE
DIR=/backups

notify() {   # 失敗才通知 Slack（有設 webhook 才送）
  [ -n "${SLACK_WEBHOOK_URL:-}" ] || return 0
  wget -q -O /dev/null --header 'Content-Type: application/json' \
       --post-data "{\"text\":\":warning: 資料庫備份失敗：$1\"}" "$SLACK_WEBHOOK_URL" || true
}

run_once() {
  ts=$(date +%Y%m%d-%H%M)
  tmp="$DIR/.quant-$ts.dump.part"; out="$DIR/quant-$ts.dump"
  echo "[backup] $(date '+%F %T') 開始 → $out"
  if pg_dump -Fc -Z 6 -f "$tmp" 2>"$DIR/.last-error.log"; then
    # 檔案要能被 pg_restore 讀出目錄，才算成功（避免半截檔）
    if pg_restore -l "$tmp" >/dev/null 2>>"$DIR/.last-error.log"; then
      mv "$tmp" "$out"; rm -f "$DIR/.last-error.log"
      echo "[backup] 完成 $(du -h "$out" | cut -f1)"
    else
      rm -f "$tmp"; echo "[backup] 驗證失敗"; notify "備份檔驗證失敗"; return 1
    fi
  else
    rm -f "$tmp"; err=$(tail -c 300 "$DIR/.last-error.log" | tr '"\n' "' ")
    echo "[backup] 失敗：$err"; notify "$err"; return 1
  fi
  find "$DIR" -maxdepth 1 -name 'quant-*.dump' -mtime +"$((BACKUP_KEEP_DAYS - 1))" -print -delete | sed 's/^/[backup] 刪除過期 /'
}

mkdir -p "$DIR"
if [ "${1:-}" = "once" ]; then run_once; exit $?; fi

# 啟動時：今天還沒備份就先備一份
ls "$DIR"/quant-"$(date +%Y%m%d)"-*.dump >/dev/null 2>&1 || run_once || true
while true; do
  now=$(date +%s)
  next=$(date -d "$(date +%F) $(printf %02d "$BACKUP_HOUR"):00:00" +%s 2>/dev/null || echo 0)
  [ "$next" -gt "$now" ] || next=$((next + 86400))
  [ "$next" -gt "$now" ] || next=$((now + 86400))
  sleep $((next - now))
  run_once || true
done
