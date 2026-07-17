#!/usr/bin/env bash
# 看門狗：確保 Docker infra 與後端服務一直在跑。
# 由 launchd 於登入時 + 每 5 分鐘執行一次。
# start-all.sh 內建「PID 還活著就略過」，所以重跑只會補起掛掉的服務。
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
if [ -x /usr/libexec/java_home ]; then
  JH="$(/usr/libexec/java_home 2>/dev/null)"; [ -n "$JH" ] && export JAVA_HOME="$JH" && export PATH="$JH/bin:$PATH"
fi

ROOT="/Users/huangyanwei/trade/quant-platform"
LOG="$ROOT/backend/logs/keepalive.log"
mkdir -p "$ROOT/backend/logs"
ts() { date "+%Y-%m-%d %H:%M:%S"; }
echo "[$(ts)] keepalive tick" >> "$LOG"

# 1) 確保 Docker 有在跑（沒跑就嘗試開 Docker Desktop 並等待）
if ! docker info >/dev/null 2>&1; then
  echo "[$(ts)] Docker 未就緒，嘗試開啟 Docker Desktop..." >> "$LOG"
  open -a Docker 2>/dev/null || true
  for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 3; done
fi
if ! docker info >/dev/null 2>&1; then
  echo "[$(ts)] Docker 仍未就緒，本輪略過" >> "$LOG"; exit 0
fi

# 2) 基礎設施（idempotent：已在跑的不動，掛掉的補起）
cd "$ROOT/infra" && { docker compose up -d >> "$LOG" 2>&1 || docker-compose up -d >> "$LOG" 2>&1; }

# 3) 等 Nacos 就緒再起後端（避免註冊失敗）
for i in $(seq 1 40); do nc -z localhost 8848 2>/dev/null && break; sleep 3; done

# 4) 後端服務（start-all.sh 只會補起沒在跑的）
cd "$ROOT/backend" && ./start-all.sh >> "$LOG" 2>&1

echo "[$(ts)] keepalive done" >> "$LOG"
