-- 滑價紀錄補上成交數量：才算得出滑價吃掉多少 USDT（不只 bps），實盤 vs 模擬 / 影子對帳用。舊資料為 NULL。
ALTER TABLE strategy_slippage ADD COLUMN IF NOT EXISTS qty DOUBLE PRECISION;
