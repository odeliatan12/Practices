package com.oms.gateway.sharding;

import org.springframework.stereotype.Component;

/**
 * ShardRouter decides which shard a request belongs to based on the userId.
 *
 * In a sharded architecture, instead of one giant database or one service instance,
 * data is split across multiple shards. The shard router is the "traffic director"
 * that tells the system which shard to talk to for a given user.
 *
 * This gateway uses hash-based sharding:
 *   shard = Math.abs(userId.hashCode()) % totalShards
 *
 * Example with 2 shards:
 *   userId "abc123" → hashCode % 2 = 0 → Shard 0 (order-service instance 1)
 *   userId "xyz789" → hashCode % 2 = 1 → Shard 1 (order-service instance 2)
 */
@Component
public class ShardRouter {

    // Total number of shards — must match number of service instances in application.yml
    private static final int TOTAL_SHARDS = 2;

    /**
     * Determines the shard index for a given userId.
     *
     * Math.abs() is used because hashCode() can return negative numbers.
     * Without it, negative % positive = negative index, which would break routing.
     *
     * Example:
     *   userId = "user-001" → hashCode = 96255 → 96255 % 2 = 1 → shard 1
     *   userId = "user-002" → hashCode = 96256 → 96256 % 2 = 0 → shard 0
     */
    public int getShardIndex(String userId) {
        return Math.abs(userId.hashCode()) % TOTAL_SHARDS;
    }

    /**
     * Maps a shard index to the actual service URL.
     *
     * In production, these URLs would come from application.yml or a service registry
     * like Eureka/Consul. Hardcoding here is for clarity only.
     *
     * Shard 0 → order-service running on port 8081 (handles users A-M roughly)
     * Shard 1 → order-service running on port 8082 (handles users N-Z roughly)
     */
    public String resolveServiceUrl(String serviceName, String userId) {
        int shardIndex = getShardIndex(userId);

        // Each service has two instances — one per shard
        // Port convention: base port + shard index
        // order-service: 8081 (shard 0), 8082 (shard 1)
        // inventory-service: 8083 (shard 0), 8084 (shard 1)
        return switch (serviceName) {
            case "order-service"     -> "http://localhost:" + (8081 + shardIndex);
            case "inventory-service" -> "http://localhost:" + (8083 + shardIndex);
            default -> throw new IllegalArgumentException("Unknown service: " + serviceName);
        };
    }
}
