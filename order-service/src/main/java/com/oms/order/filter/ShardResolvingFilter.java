package com.oms.order.filter;

import com.oms.order.sharding.ShardContext;
import com.oms.order.sharding.ShardResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ShardResolvingFilter runs on every incoming request and sets the shard context
 * so that all database calls within that request go to the correct shard.
 *
 * It reads the X-User-Id header that the API gateway's AuthFilter injects after
 * validating the JWT. This means:
 *   - The gateway handles authentication (who are you?)
 *   - This filter handles shard routing (which DB has your data?)
 *
 * OncePerRequestFilter guarantees this filter runs exactly once per request —
 * not once per servlet forward or include. Prevents double-routing.
 *
 * Filter lifecycle per request:
 *   1. doFilterInternal() called — set shard in ThreadLocal
 *   2. Request processed — all DB calls use the correct shard
 *   3. finally block — clear ThreadLocal regardless of success or error
 */
@Component
public class ShardResolvingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ShardResolvingFilter.class);

    // Default shard used when no userId is present (health checks, internal calls)
    private static final String DEFAULT_SHARD = "shard_0";

    private final ShardResolver shardResolver;

    public ShardResolvingFilter(ShardResolver shardResolver) {
        this.shardResolver = shardResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // X-User-Id is injected by the API gateway's AuthFilter after JWT validation
        // It contains the userId ("sub" claim) extracted from the JWT
        String userId = request.getHeader("X-User-Id");

        try {
            if (userId != null && !userId.isEmpty()) {
                // Hash the userId to determine which shard holds this user's orders
                String shard = shardResolver.resolve(userId);
                ShardContext.setShard(shard);
                log.debug("Shard resolved: userId={} shard={}", userId, shard);
            } else {
                // No userId — health check, actuator, or internal call
                // Route to shard_0 as a safe default
                ShardContext.setShard(DEFAULT_SHARD);
                log.debug("No X-User-Id header found, defaulting to {}", DEFAULT_SHARD);
            }

            // Continue the filter chain — request proceeds with shard context set
            filterChain.doFilter(request, response);

        } finally {
            // Always clear the shard context after the request completes.
            // Threads are reused in a pool — without this, the next request on
            // this thread inherits the wrong shard and queries the wrong database.
            ShardContext.clear();
        }
    }
}
