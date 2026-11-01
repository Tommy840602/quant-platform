-- 訂單記錄當時的環境：testnet / mainnet。策略倉位對帳、歸因只看目前環境，不讓測試網的舊單混進正式網。
ALTER TABLE orders ADD COLUMN IF NOT EXISTS env TEXT NOT NULL DEFAULT 'testnet';
CREATE INDEX IF NOT EXISTS idx_orders_env_strategy ON orders (env, strategy_id) WHERE strategy_id IS NOT NULL;
