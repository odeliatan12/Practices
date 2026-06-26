package com.oms.gateway.sharding;

/**
 * ShardContext holds the current shard information for a request.
 *
 * In a traditional Spring MVC (blocking) application, ThreadLocal is used
 * to store per-request data because each request runs on its own dedicated thread:
 *
 *   Request 1 → Thread A → ThreadLocal holds "shard-0"
 *   Request 2 → Thread B → ThreadLocal holds "shard-1"
 *
 * However, this gateway uses Spring WebFlux (reactive/non-blocking).
 * In WebFlux, one thread handles many requests — a request can switch threads
 * mid-flight, so ThreadLocal would lose the shard value between steps.
 *
 * The correct solution in WebFlux is Reactor Context (similar to ThreadLocal
 * but attached to the reactive chain, not the thread). This class shows the
 * ThreadLocal pattern for learning purposes, with notes on the WebFlux alternative.
 *
 * In production WebFlux code, use:
 *   Mono.deferContextual(ctx -> ctx.get("shardIndex"))
 * instead of ShardContext.getCurrentShard().
 */
public class ShardContext {

    // ThreadLocal stores one value per thread
    // Each thread has its own isolated copy — no sharing between threads
    private static final ThreadLocal<Integer> currentShardIndex = new ThreadLocal<>();

    /**
     * Sets the shard index for the current thread.
     * Called before any database or service call is made.
     */
    public static void setShardIndex(int shardIndex) {
        currentShardIndex.set(shardIndex);
    }

    /**
     * Gets the shard index for the current thread.
     * Returns -1 if no shard has been set (no routing context available).
     */
    public static int getShardIndex() {
        Integer index = currentShardIndex.get();
        return index != null ? index : -1;
    }

    /**
     * Clears the shard index after the request completes.
     *
     * IMPORTANT — always call this after each request.
     * ThreadLocals are attached to threads, not requests. In a thread pool,
     * threads are reused across requests. Without clearing, the next request
     * on the same thread inherits the previous request's shard — routing to
     * the wrong shard silently.
     */
    public static void clear() {
        currentShardIndex.remove();
    }
}
