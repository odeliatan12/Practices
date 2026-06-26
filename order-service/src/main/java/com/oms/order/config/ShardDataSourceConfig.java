package com.oms.order.config;

import com.oms.order.sharding.ShardResolver;
import com.oms.order.sharding.ShardRoutingDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;

/**
 * ShardDataSourceConfig registers two PostgreSQL datasources — one per shard —
 * and wires them into ShardRoutingDataSource so Spring/JPA uses the correct one
 * per request automatically.
 *
 * Shard layout:
 *   shard_0 → orders_db_shard0 on port 5433 (users whose hashCode % 2 == 0)
 *   shard_1 → orders_db_shard1 on port 5434 (users whose hashCode % 2 == 1)
 *
 * From JPA and Hibernate's perspective, there is only one DataSource — they
 * are unaware sharding exists. ShardRoutingDataSource intercepts every connection
 * request and transparently hands back the correct physical datasource.
 *
 * application.yml must define:
 *   spring.datasource.shard0.*  — connection details for shard 0
 *   spring.datasource.shard1.*  — connection details for shard 1
 */
@Configuration
public class ShardDataSourceConfig {

    /**
     * Shard 0 datasource — bound to spring.datasource.shard0.* in application.yml.
     * Handles all orders for users whose hashCode % 2 == 0.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.shard0")
    public DataSource shard0DataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * Shard 1 datasource — bound to spring.datasource.shard1.* in application.yml.
     * Handles all orders for users whose hashCode % 2 == 1.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.shard1")
    public DataSource shard1DataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * ShardRoutingDataSource is the single DataSource that Spring/JPA sees.
     *
     * targetDataSources — the map of shard keys to physical datasources.
     *   ShardResolver.SHARD_0 ("shard_0") → shard0DataSource
     *   ShardResolver.SHARD_1 ("shard_1") → shard1DataSource
     *
     * defaultTargetDataSource — used when determineCurrentLookupKey() returns null.
     *   Prevents NullPointerException if ShardContext was not set (e.g. during startup).
     *
     * @Primary tells Spring to inject this when any component asks for a DataSource.
     * Without @Primary, Spring would be confused by the three DataSource beans.
     */
    @Bean
    @Primary
    public DataSource routingDataSource(DataSource shard0DataSource, DataSource shard1DataSource) {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

        // Register shard key → physical datasource mappings
        routingDataSource.setTargetDataSources(Map.of(
                ShardResolver.SHARD_0, shard0DataSource,
                ShardResolver.SHARD_1, shard1DataSource
        ));

        // Fall back to shard_0 if no shard context is set
        routingDataSource.setDefaultTargetDataSource(shard0DataSource);

        return routingDataSource;
    }
}
