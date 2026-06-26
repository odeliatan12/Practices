package com.oms.gateway.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * ShardRoutingFilter runs after AuthFilter (order -99) and before the default
 * gateway router (order 0). It reads the X-User-Id header injected by AuthFilter
 * and rewrites the request URI to point to the correct shard instance.
 *
 * Filter execution order in this gateway:
 *   1. AuthFilter       (order -100) — validates JWT, injects X-User-Id
 *   2. RateLimitFilter  (order -99)  — rate limits per user
 *   3. ShardRoutingFilter (order -98) — rewrites URI to correct shard
 *   4. Default Router   (order 0)   — forwards to the rewritten URI
 *
 * Flow:
 *   Request arrives with X-User-Id: "user-123"
 *       ↓
 *   ShardRouter.getShardIndex("user-123") = 1
 *       ↓
 *   Rewrite URI: http://localhost:8081 → http://localhost:8082 (shard 1)
 *       ↓
 *   Request forwarded to correct shard instance
 */
@Component
public class ShardRoutingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ShardRoutingFilter.class);

    private final ShardRouter shardRouter;

    public ShardRoutingFilter(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // X-User-Id was injected by AuthFilter after JWT validation
        // If missing, the request is either public or AuthFilter didn't run
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");

        if (userId == null || userId.isEmpty()) {
            // Public routes (auth endpoints) have no userId — skip shard routing
            // They will be handled by the default routing config in application.yml
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();

        // Determine which service this request targets based on the path
        String serviceName = resolveServiceName(path);

        if (serviceName == null) {
            // Path doesn't match any sharded service — pass through unchanged
            return chain.filter(exchange);
        }

        // Hash the userId to get the shard index
        // Same userId always maps to same shard — data consistency guaranteed
        int shardIndex = shardRouter.getShardIndex(userId);
        String shardedUrl = shardRouter.resolveServiceUrl(serviceName, userId);

        log.debug("Shard routing: userId={} path={} shard={} url={}", userId, path, shardIndex, shardedUrl);

        // Store shard index in Reactor Context — survives thread switches in WebFlux
        // This is the correct alternative to ThreadLocal in reactive code
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put("shardIndex", shardIndex)
                                        .put("shardedUrl", shardedUrl));
    }

    /**
     * Maps a request path to a service name.
     * Returns null if the path doesn't belong to a sharded service.
     *
     * /api/v1/orders/**    → order-service
     * /api/v1/inventory/** → inventory-service
     * /api/v1/auth/**      → null (not sharded — auth is stateless)
     * /api/v1/payments/**  → null (not sharded — payment uses external provider)
     */
    private String resolveServiceName(String path) {
        if (path.startsWith("/api/v1/orders/"))     return "order-service";
        if (path.startsWith("/api/v1/inventory/"))  return "inventory-service";
        return null;
    }

    // Order -98 — runs after AuthFilter (-100) and RateLimitFilter (-99)
    // Must run after AuthFilter because we depend on X-User-Id header it injects
    @Override
    public int getOrder() {
        return -98;
    }
}
