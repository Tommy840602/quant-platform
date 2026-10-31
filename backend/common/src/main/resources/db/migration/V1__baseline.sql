-- ============================================================================
-- V1 基線：把 infra/init/timescale-init.sql + 各服務 ddl-auto=update 偷偷補的欄位 + auth 手寫的 users
-- 全部收進 Flyway。每一句都可重跑（IF NOT EXISTS），既有資料庫 baseline-version=0 後執行也安全。
-- 放在 common jar：有接資料庫的服務都會跑同一份；Flyway 以 flyway_schema_history 鎖避免併發。
-- ============================================================================

-- 行情：K 線（TimescaleDB 超表；extension 由 infra/init/timescale-init.sql 建）
CREATE TABLE IF NOT EXISTS kline (
    symbol       TEXT             NOT NULL,
    interval     TEXT             NOT NULL,
    open_time    TIMESTAMPTZ      NOT NULL,
    open         DOUBLE PRECISION,
    high         DOUBLE PRECISION,
    low          DOUBLE PRECISION,
    close        DOUBLE PRECISION,
    volume       DOUBLE PRECISION,
    quote_volume DOUBLE PRECISION,
    PRIMARY KEY (symbol, interval, open_time)
);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('kline', 'open_time', if_not_exists => TRUE, migrate_data => TRUE);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_kline_symbol_interval ON kline (symbol, interval, open_time DESC);

-- 訂單
CREATE TABLE IF NOT EXISTS orders (
    id                BIGSERIAL PRIMARY KEY,
    client_order_id   TEXT UNIQUE NOT NULL,
    exchange_order_id TEXT,
    symbol            TEXT NOT NULL,
    side              TEXT NOT NULL,
    type              TEXT NOT NULL,
    price             DOUBLE PRECISION,
    quantity          DOUBLE PRECISION NOT NULL,
    filled_qty        DOUBLE PRECISION DEFAULT 0,
    avg_price         DOUBLE PRECISION DEFAULT 0,
    status            TEXT NOT NULL,
    strategy_id       TEXT,
    account           TEXT NOT NULL DEFAULT 'main',
    market            TEXT NOT NULL DEFAULT 'SPOT',
    created_at        TIMESTAMPTZ DEFAULT now(),
    updated_at        TIMESTAMPTZ DEFAULT now()
);
-- 舊庫由 init.sql 建的 orders 沒有這兩欄（之前靠 ddl-auto=update 補）
ALTER TABLE orders ADD COLUMN IF NOT EXISTS avg_price DOUBLE PRECISION DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS market    TEXT NOT NULL DEFAULT 'SPOT';
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_symbol ON orders (symbol);
CREATE INDEX IF NOT EXISTS idx_orders_strategy ON orders (strategy_id) WHERE strategy_id IS NOT NULL;

-- 持倉
CREATE TABLE IF NOT EXISTS positions (
    account        TEXT NOT NULL,
    symbol         TEXT NOT NULL,
    quantity       DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_price      DOUBLE PRECISION NOT NULL DEFAULT 0,
    unrealized_pnl DOUBLE PRECISION DEFAULT 0,
    updated_at     TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (account, symbol)
);

-- Outbox（保留：#18 冪等 outbox 會用）
CREATE TABLE IF NOT EXISTS order_outbox (
    id              BIGSERIAL PRIMARY KEY,
    client_order_id TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- 資金曲線
CREATE TABLE IF NOT EXISTS equity_curve (
    id      BIGSERIAL PRIMARY KEY,
    account TEXT NOT NULL DEFAULT 'main',
    ts      TIMESTAMPTZ NOT NULL DEFAULT now(),
    equity  DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_equity_ts ON equity_curve (ts DESC);

-- 平台帳號（原本由 auth-service UserService.bootstrap 手寫 CREATE TABLE）
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    roles         TEXT NOT NULL DEFAULT 'TRADER',
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ DEFAULT now(),
    last_login_at TIMESTAMPTZ
);
