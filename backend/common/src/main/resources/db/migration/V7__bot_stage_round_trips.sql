-- 晉升管線：PAPER（模擬盤）→ PILOT（真錢最小單）→ LIVE。既有機器人預設 LIVE（行為不變），新建的由程式設為 PAPER。
ALTER TABLE strategy_bots ADD COLUMN IF NOT EXISTS stage TEXT NOT NULL DEFAULT 'LIVE';
ALTER TABLE strategy_bots ADD COLUMN IF NOT EXISTS stage_since TIMESTAMPTZ NOT NULL DEFAULT now();

-- 來回交易紀錄：LIVE（實盤成交）、PAPER（模擬盤）、SHADOW（實盤機器人的理想成交分身）。
-- 晉升關卡、自動配資重啟後的學習、實盤 vs 影子差距都從這裡算。
CREATE TABLE IF NOT EXISTS strategy_round_trips (
    id        BIGSERIAL PRIMARY KEY,
    bot_id    TEXT NOT NULL,
    symbol    TEXT NOT NULL,
    mode      TEXT NOT NULL,
    qty       DOUBLE PRECISION NOT NULL,
    entry_px  DOUBLE PRECISION NOT NULL,
    exit_px   DOUBLE PRECISION NOT NULL,
    pnl       DOUBLE PRECISION NOT NULL,
    ret       DOUBLE PRECISION NOT NULL,
    closed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_round_trips_bot ON strategy_round_trips (bot_id, mode, closed_at);

-- 每筆實盤成交的滑價：送單當下參考價 vs 實際成交價（bps，正 = 對我們不利）。
CREATE TABLE IF NOT EXISTS strategy_slippage (
    id      BIGSERIAL PRIMARY KEY,
    bot_id  TEXT NOT NULL,
    symbol  TEXT NOT NULL,
    side    TEXT NOT NULL,
    ref_px  DOUBLE PRECISION NOT NULL,
    fill_px DOUBLE PRECISION NOT NULL,
    bps     DOUBLE PRECISION NOT NULL,
    ts      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_slippage_bot ON strategy_slippage (bot_id, ts);
