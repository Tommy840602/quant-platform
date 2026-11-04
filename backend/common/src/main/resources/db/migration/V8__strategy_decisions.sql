-- 決策日誌：每次「有訊號」的處理結果（送單 / 模擬成交 / 出場 / 被風控、盤勢、持倉上限、冷卻擋下 / 被拒），
-- 連同當下的盤勢指標。用來回答「它為什麼在這裡買 / 為什麼沒買」，並在 K 線圖上回放。保留 30 天。
CREATE TABLE IF NOT EXISTS strategy_decisions (
    id       BIGSERIAL PRIMARY KEY,
    ts       TIMESTAMPTZ NOT NULL DEFAULT now(),
    bot_id   TEXT NOT NULL,
    symbol   TEXT NOT NULL,
    side     TEXT,
    price    DOUBLE PRECISION,
    acted    BOOLEAN NOT NULL DEFAULT FALSE,
    outcome  TEXT NOT NULL,
    detail   JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_decisions_bot_ts ON strategy_decisions (bot_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_decisions_sym_ts ON strategy_decisions (symbol, ts DESC);
