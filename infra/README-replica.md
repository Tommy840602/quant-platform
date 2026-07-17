# PostgreSQL 唯讀副本（讀寫分離）

讀寫分離已在 order-execution 以 `ReadWriteDataSourceConfig` 實作（`@Transactional(readOnly=true)` 走副本）。
預設關閉；要啟用：

1. 建立 PostgreSQL 主/副流複製（streaming replication）。開發可用 Bitnami 映像快速起副本：
   ```yaml
   # 於 infra/docker-compose.yml 增加（示意）
   pg-replica:
     image: bitnami/postgresql:16
     environment:
       POSTGRESQL_REPLICATION_MODE: slave
       POSTGRESQL_MASTER_HOST: timescaledb
       POSTGRESQL_REPLICATION_USER: repl
       POSTGRESQL_REPLICATION_PASSWORD: repl
       POSTGRESQL_MASTER_PORT_NUMBER: "5432"
     ports: ["5434:5432"]
   ```
   （主庫需開啟 wal_level=replica、建立 repl 角色；正式建議用託管 RDS/Cloud SQL 讀取副本。）

2. 於 order-execution 環境變數啟用：
   ```
   DB_RW_SPLIT=true
   DB_READ_HOST=localhost
   DB_READ_PORT=5434
   ```
3. 重啟 order-execution。查詢類交易（唯讀）會走副本，寫入走主庫。
