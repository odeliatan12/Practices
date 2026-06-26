package com.oms.order.sharding;

/**
 * ShardContext uses ThreadLocal to store which shard the current request belongs to.
 *
 * This works here because order-service is a standard Spring MVC (blocking) service —
 * each HTTP request runs on exactly one dedicated thread from start to finish.
 * ThreadLocal stores one value per thread, so each request gets its own isolated shard key.
 *
 * ThreadLocal is safe here because:
 *   Request 1 → Thread A → ShardContext holds "shard_0"
 *   Request 2 → Thread B → ShardContext holds "shard_1"
 *   Both run simultaneously with no interference.
 *
 * IMPORTANT — clear() must be called after every request (done in ShardResolvingFilter).
 * Threads in a pool are reused across requests. Without clearing, the next request
 * on the same thread inherits the previous shard key — silently querying the wrong DB.
 */
public class ShardContext {

    // One shard key stored per thread — completely isolated between threads
    private static final ThreadLocal<String> currentShard = new ThreadLocal<>();

    public static void setShard(String shardKey) {
        currentShard.set(shardKey);
    }

    public static String getShard() {
        return currentShard.get();
    }

    // Called at the end of every request to prevent stale shard leaking to the next request
    public static void clear() {
        currentShard.remove();
    }
}
