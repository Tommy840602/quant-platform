-- TimescaleDB 初始化：行情時序表 + 交易/帳務表
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ============ 行情：K 線（時序超表）============
CREATE TABLE IF NOT EXISTS kline (
    symbol      TEXT        NOT NULL,
    interval    TEXT        NOT NULL,           -- 1m,5m,1h,1d
    open_time   TIMESTAMPTZ NOT NULL,
    open        DOUBLE PRECISION,
    high        DOUBLE PRECISION,
    low         DOUBLE PRECISION,
    close       DOUBLE PRECISION,
    volume      DOUBLE PRECISION,
    quote_volume DOUBLE PRECISION,
    PRIMARY KEY (symbol, interval, open_time)
);
SELECT create_hypertable('kline', 'open_time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_kline_symbol_interval ON kline (symbol, interval, open_time DESC);

-- 連續彙總示例：由 1m 聚合 1h（實務可再建 5m/1d）
-- CREATE MATERIALIZED VIEW kline_1h WITH (timescaledb.continuous) AS
--   SELECT symbol, time_bucket('1 hour', open_time) AS bucket,
--          first(open, open_time) AS open, max(high) AS high,
--          min(low) AS low, last(close, open_time) AS close, sum(volume) AS volume
--   FROM kline WHERE interval = '1m' GROUP BY symbol, bucket;

-- ============ 交易 / 帳務 ============
CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL PRIMARY KEY,
    client_order_id TEXT UNIQUE NOT NULL,       -- 冪等鍵
    exchange_order_id TEXT,
    symbol          TEXT NOT NULL,
    side            TEXT NOT NULL,              -- BUY/SELL
    type            TEXT NOT NULL,              -- MARKET/LIMIT/STOP
    price           DOUBLE PRECISION,
    quantity        DOUBLE PRECISION NOT NULL,
    filled_qty      DOUBLE PRECISION DEFAULT 0,
    status          TEXT NOT NULL,              -- 狀態機
    strategy_id     TEXT,
    account         TEXT NOT NULL DEFAULT 'main',
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_symbol ON orders (symbol);

CREATE TABLE IF NOT EXISTS positions (
    account     TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    quantity    DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_price   DOUBLE PRECISION NOT NULL DEFAULT 0,
    unrealized_pnl DOUBLE PRECISION DEFAULT 0,
    updated_at  TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (account, symbol)
);

-- Outbox：先落地下單意圖，再送交易所（崩潰安全）
CREATE TABLE IF NOT EXISTS order_outbox (
    id          BIGSERIAL PRIMARY KEY,
    client_order_id TEXT NOT NULL,
    payload     JSONB NOT NULL,
    status      TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/SENT/FAILED
    created_at  TIMESTAMPTZ DEFAULT now()
);

-- 資金曲線
CREATE TABLE IF NOT EXISTS equity_curve (
    id        BIGSERIAL PRIMARY KEY,
    account   TEXT NOT NULL DEFAULT 'main',
    ts        TIMESTAMPTZ NOT NULL DEFAULT now(),
    equity    DOUBLE PRECISION NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_equity_ts ON equity_curve (ts DESC);
