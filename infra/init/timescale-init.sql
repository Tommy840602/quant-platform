-- 容器第一次建庫時執行（只跑一次）。
-- 只負責啟用 extension；所有表結構改由 Flyway 管理：backend/common/src/main/resources/db/migration/
CREATE EXTENSION IF NOT EXISTS timescaledb;
