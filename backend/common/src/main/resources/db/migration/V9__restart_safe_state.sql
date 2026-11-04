-- 重啟不遺失的機器人狀態：
--   PAPER  模擬盤部位（數量 / 成本）
--   SHADOW 影子雙胞胎部位
--   PEAK   實盤或模擬盤持倉「進場後最高價」（移動停利的基準；重啟後不會被重設成現價）
CREATE TABLE IF NOT EXISTS strategy_position_state (
    bot_id     TEXT NOT NULL,
    symbol     TEXT NOT NULL,
    kind       TEXT NOT NULL,
    qty        DOUBLE PRECISION NOT NULL DEFAULT 0,
    entry_px   DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak       DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bot_id, symbol, kind)
);
-- 熔斷暫停原因（NULL = 未暫停）。重啟後熔斷中的機器人維持暫停，不會自己恢復進場。
ALTER TABLE strategy_bots ADD COLUMN IF NOT EXISTS paused_reason TEXT;
