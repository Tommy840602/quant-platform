-- 帳務正確性（review #1~#3）。整支可重跑（CI 會以 baseline 0 再跑一次全部 migration）。

-- #1 執行模式獨立欄位：不再借 exchange_order_id = 'DRYRUN' 判斷模擬 / 真單
ALTER TABLE orders ADD COLUMN IF NOT EXISTS execution_mode TEXT;
UPDATE orders SET execution_mode = CASE WHEN exchange_order_id LIKE 'DRYRUN%' THEN 'DRY' ELSE 'LIVE' END
 WHERE execution_mode IS NULL;
CREATE INDEX IF NOT EXISTS idx_orders_env_mode ON orders (env, execution_mode);

-- #2 事件去重與入帳同一筆 DB transaction：(consumer, event_id) 唯一
CREATE TABLE IF NOT EXISTS processed_events (
    consumer     TEXT NOT NULL,
    event_id     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
CREATE INDEX IF NOT EXISTS idx_processed_events_ts ON processed_events (processed_at);

-- #2 現金帳改 DB：餘額表 + 可重播的分錄（Redis 只當舊值遷移來源）
CREATE TABLE IF NOT EXISTS cash_ledger (
    account        TEXT NOT NULL,
    env            TEXT NOT NULL,
    execution_mode TEXT NOT NULL,
    cash           DOUBLE PRECISION NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account, env, execution_mode)
);
CREATE TABLE IF NOT EXISTS cash_entries (
    id             BIGSERIAL PRIMARY KEY,
    account        TEXT NOT NULL,
    env            TEXT NOT NULL,
    execution_mode TEXT NOT NULL,
    event_id       TEXT,
    delta          DOUBLE PRECISION NOT NULL,
    balance_after  DOUBLE PRECISION NOT NULL,
    note           TEXT,
    ts             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cash_entries_acct ON cash_entries (account, env, execution_mode, ts);

-- #3 部位鍵補齊 env / 市場 / 執行模式：現貨與合約、模擬與真單、測試網與正式網不再混在同一列
ALTER TABLE positions ADD COLUMN IF NOT EXISTS env            TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE positions ADD COLUMN IF NOT EXISTS market         TEXT NOT NULL DEFAULT 'SPOT';
ALTER TABLE positions ADD COLUMN IF NOT EXISTS execution_mode TEXT NOT NULL DEFAULT 'LIVE';
ALTER TABLE positions DROP CONSTRAINT IF EXISTS positions_pkey;
ALTER TABLE positions ADD CONSTRAINT positions_pkey PRIMARY KEY (account, env, execution_mode, market, symbol);
