package com.oms.order.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * ShardRoutingDataSource extends Spring's AbstractRoutingDataSource to dynamically
 * switch between shard databases on every request.
 *
 * How AbstractRoutingDataSource works:
 *   1. You register multiple datasources with lookup keys (shard_0, shard_1)
 *   2. On every DB call, Spring calls determineCurrentLookupKey()
 *   3. The returned key selects which datasource to use for this DB call
 *
 * It acts as a single datasource from JPA/Hibernate's perspective — they have
 * no idea multiple databases exist. The routing is completely transparent.
 *
 * Flow:
 *   HTTP request arrives
 *       ↓
 *   ShardResolvingFilter sets ShardContext to "shard_0"
 *       ↓
 *   OrderService calls orderRepository.findById(...)
 *       ↓
 *   JPA asks ShardRoutingDataSource for a connection
 *       ↓
 *   determineCurrentLookupKey() returns "shard_0"
 *       ↓
 *   Connection given from shard_0 datasource (orders_db_shard0, port 5433)
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * Called automatically by Spring before every database operation.
     * Returns the lookup key that maps to the correct shard datasource.
     *
     * ShardContext.getShard() reads the ThreadLocal value set by ShardResolvingFilter
     * for this specific request thread — guaranteeing each request queries its own shard.
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return ShardContext.getShard();
    }
}
