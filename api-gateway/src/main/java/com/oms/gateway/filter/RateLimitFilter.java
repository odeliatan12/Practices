package com.oms.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.time.Duration;

/**
 * Redis sliding-window rate limiter.
 *
 * Key is either X-User-Id (authenticated) or the client IP (anonymous).
 * Runs AFTER AuthFilter so X-User-Id is already in the request headers.
 *
 * Limits (per minute):
 *   Authenticated users → 1000 req/min
 *   Anonymous (by IP)   →   60 req/min
 *   Auth endpoints      →   10 req/min (extra tight — brute force protection)
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int AUTHENTICATED_LIMIT = 1000;
    private static final int ANONYMOUS_LIMIT      = 60;
    private static final int AUTH_ENDPOINT_LIMIT  = 10;
    private static final Duration WINDOW          = Duration.ofMinutes(1);

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public RateLimitFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path   = exchange.getRequest().getURI().getPath();
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        String ip     = getClientIp(exchange);

        // Determine rate limit key and threshold
        String key;
        int limit;

        if (path.startsWith("/api/v1/auth/")) {
            // tightest limit — OTP, login brute force protection
            key   = "rl:auth:" + ip;
            limit = AUTH_ENDPOINT_LIMIT;
        } else if (userId != null && !userId.isBlank()) {
            // authenticated user — keyed by userId (consistent across pods)
            key   = "rl:user:" + userId;
            limit = AUTHENTICATED_LIMIT;
        } else {
            // anonymous request — keyed by IP
            key   = "rl:ip:" + ip;
            limit = ANONYMOUS_LIMIT;
        }

        return redisTemplate.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        // First request in this window — set TTL
                        return redisTemplate.expire(key, WINDOW)
                                .then(proceed(exchange, chain, count, limit, key));
                    }
                    return proceed(exchange, chain, count, limit, key);
                });
    }

    private Mono<Void> proceed(ServerWebExchange exchange, GatewayFilterChain chain,
                                Long count, int limit, String key) {
        if (count > limit) {
            log.warn("Rate limit exceeded key={} count={} limit={}", key, count, limit);
            return writeTooManyRequests(exchange);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        exchange.getResponse().getHeaders().add("Retry-After", "60");
        byte[] body = "{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many requests. Try again in 60 seconds.\"}".getBytes();
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private String getClientIp(ServerWebExchange exchange) {
        // X-Forwarded-For is set by the CDN/load balancer in front of the gateway
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim(); // first IP in the chain is the real client
        }
        var address = exchange.getRequest().getRemoteAddress();
        return address != null ? address.getAddress().getHostAddress() : "unknown";
    }

    // Runs after AuthFilter (order -100) so X-User-Id header is already present
    @Override
    public int getOrder() {
        return -99;
    }
}
