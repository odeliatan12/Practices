package com.oms.order.sharding;

import org.springframework.stereotype.Component;

/**
 * ShardResolver maps a userId to a shard key using hash-based sharding.
 *
 * Hash-based sharding formula:
 *   shardIndex = Math.abs(userId.hashCode()) % totalShards
 *
 * The same userId always produces the same shard index because:
 *   - hashCode() is deterministic for the same string value
 *   - totalShards never changes at runtime
 *
 * This guarantees data locality — all orders for user-123 are always
 * written to and read from the same shard. No cross-shard lookups needed.
 *
 * Example:
 *   userId "550e8400-e29b-41d4-a716-446655440000"
 *   → hashCode = 1234567
 *   → Math.abs(1234567) % 2 = 1
 *   → shard_1 → points to orders_db_shard1 (port 5434)
 */
@Component
public class ShardResolver {

    // Must match the number of datasources configured in ShardDataSourceConfig
    private static final int TOTAL_SHARDS = 2;

    // Shard key constants — used as lookup keys in AbstractRoutingDataSource
    public static final String SHARD_0 = "shard_0";
    public static final String SHARD_1 = "shard_1";

    /**
     * Resolves the shard key for a given userId.
     *
     * Math.abs() prevents negative shard indices — hashCode() can return negative values
     * and negative % positive = negative in Java, which would not match any shard key.
     */
    public String resolve(String userId) {
        int shardIndex = Math.abs(userId.hashCode()) % TOTAL_SHARDS;
        return "shard_" + shardIndex;
    }
}
