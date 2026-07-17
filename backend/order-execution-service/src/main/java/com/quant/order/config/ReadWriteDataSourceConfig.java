package com.quant.order.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 資料庫讀寫分離（見架構文件 §5）：@Transactional(readOnly=true) 的查詢走唯讀副本，其餘走主庫。
 * 僅在 datasource.readwrite-split.enabled=true 時啟用；預設關閉沿用 Spring Boot 單一資料源。
 *
 * 設定範例（application.yml）：
 *   datasource:
 *     readwrite-split:
 *       enabled: true
 *   app:
 *     datasource:
 *       write: { url: jdbc:postgresql://master:5432/quant, username: quant, password: quant }
 *       read:  { url: jdbc:postgresql://replica:5432/quant, username: quant, password: quant }
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "datasource.readwrite-split.enabled", havingValue = "true")
public class ReadWriteDataSourceConfig {

    @Bean @ConfigurationProperties("app.datasource.write")
    public DataSource writeDataSource() { return DataSourceBuilder.create().type(HikariDataSource.class).build(); }

    @Bean @ConfigurationProperties("app.datasource.read")
    public DataSource readDataSource() { return DataSourceBuilder.create().type(HikariDataSource.class).build(); }

    @Bean @Primary
    public DataSource routingDataSource(DataSource writeDataSource, DataSource readDataSource) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override protected Object determineCurrentLookupKey() {
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "read" : "write";
            }
        };
        routing.setDefaultTargetDataSource(writeDataSource);
        routing.setTargetDataSources(Map.of("write", writeDataSource, "read", readDataSource));
        routing.afterPropertiesSet();
        // Lazy proxy：直到真正取得連線才依交易唯讀性決定路由
        return new LazyConnectionDataSourceProxy(routing);
    }
}
