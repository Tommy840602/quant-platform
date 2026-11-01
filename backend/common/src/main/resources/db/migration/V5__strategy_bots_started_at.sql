-- 機器人本次上線時間：執行時長與「本次上線以來」的收益統計用；下線清空
ALTER TABLE strategy_bots ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;
