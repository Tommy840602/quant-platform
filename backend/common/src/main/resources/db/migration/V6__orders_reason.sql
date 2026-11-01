-- 拒單 / 狀態不明的原因（風控、幣安 filter、餘額…），讓訂單紀錄頁直接看得到為什麼。
ALTER TABLE orders ADD COLUMN IF NOT EXISTS reason TEXT;
