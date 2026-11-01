-- 策略機器人持久化：參數、標的、資金配置、是否運行。重啟後由 strategy-service 讀回並恢復 RUNNING 的機器人。
CREATE TABLE IF NOT EXISTS strategy_bots (
    id          TEXT PRIMARY KEY,
    type        TEXT NOT NULL,
    name        TEXT NOT NULL,
    symbol      TEXT,                          -- NULL = 全部訂閱標的
    params      JSONB NOT NULL DEFAULT '{}'::jsonb,
    allocation  DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    running     BOOLEAN NOT NULL DEFAULT FALSE,
    builtin     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);
-- 服務層級設定（每單名目等）
CREATE TABLE IF NOT EXISTS strategy_settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT now()
);
