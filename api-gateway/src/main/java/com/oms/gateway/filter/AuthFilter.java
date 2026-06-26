package com.oms.gateway.filter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;

/**
 * First filter in the gateway chain (order = -100).
 *
 * Two responsibilities:
 *   1. Validate the JWT on every protected request.
 *   2. Convert the token into plain HTTP headers (X-User-Id, X-User-Roles, X-Country)
 *      so downstream services never need to touch cryptography.
 *
 * Flow:
 *   Public path?  → pass through immediately (no token needed)
 *   No Bearer?    → 401
 *   Bad/expired?  → 401
 *   Valid token   → inject headers, strip Authorization, forward to next filter
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final RedisTemplate<String, String> redisTemplate;


    /**
     * Paths that do not require a JWT.
     *
     * /api/v1/auth/ is public because the user is still in the process of getting a token
     * (login, OTP, refresh). Requiring a token here would be a chicken-and-egg problem.
     *
     * /actuator/ is public so Kubernetes liveness/readiness probes can reach /actuator/health
     * without a token — otherwise the gateway pod would fail health checks and be restarted.
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/",
            "/actuator/"
    );

    // Injected by JwtConfig — handles signature verification and expiry checks
    private final ReactiveJwtDecoder jwtDecoder;

    public AuthFilter(ReactiveJwtDecoder jwtDecoder, RedisTemplate<String, String> redisTemplate) {
        this.jwtDecoder = jwtDecoder;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Mono<Void> is the reactive return type — think of it as a promise that completes
     * with no value. The gateway is non-blocking (WebFlux), so every operation returns
     * a Mono/Flux instead of blocking the thread.
     *
     * ServerWebExchange = the current request + response pair (one object holds both).
     * GatewayFilterChain = the queue of filters that come after this one.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Public routes bypass all JWT logic — return immediately to avoid unnecessary work
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // Standard HTTP Bearer token format: "Authorization: Bearer <jwt>"
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or malformed Authorization header for path={}", path);
            return writeUnauthorized(exchange, "Missing or invalid Authorization header");
        }

        // Drop "Bearer " prefix (7 characters) to get the raw JWT string
        String token = authHeader.substring(7);

        // jwtDecoder.decode() is non-blocking — it returns a Mono<Jwt>
        // It verifies: (1) the signature matches our secret, (2) the token is not expired
        return jwtDecoder.decode(token)
                .flatMap(jwt -> {
                    // flatMap runs only when decode() succeeds (valid, non-expired token)
                    // If decode() fails it jumps straight to onErrorResume below

                    // "sub" claim = the user's UUID set by the auth service at login time
                    String userId = jwt.getSubject();

                    // Custom claims we added to the token payload (e.g. ["ROLE_BUYER"])
                    List<String> roles = jwt.getClaimAsStringList("roles");

                    // Drives region-specific behaviour (tax rules, compliance, currency)
                    String country = jwt.getClaimAsString("countryCode");

                    log.debug("Authenticated request: userId={} path={}", userId, path);

                    // ServerHttpRequest is immutable in WebFlux — .mutate() creates a new
                    // copy with the changes applied, leaving the original unchanged
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            // These three headers let downstream services identify the caller
                            // without any cryptographic work on their end
                            .header("X-User-Id",    userId  != null ? userId : "")
                            .header("X-User-Roles", roles   != null ? String.join(",", roles) : "")
                            .header("X-Country",    country != null ? country : "SG")
                            // Strip the raw token so it is never logged or stored
                            // by order-service, inventory-service, etc.
                            .headers(h -> h.remove("Authorization"))
                            .build();

                    // Forward the modified request to the next filter (RateLimitFilter → Router)
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                // onErrorResume catches JwtException (bad signature, expired, malformed)
                // without letting the exception propagate up the reactive chain
                .onErrorResume(JwtException.class, ex -> {
                    log.warn("JWT validation failed for path={}: {}", path, ex.getMessage());
                    return writeUnauthorized(exchange, "Invalid or expired token");
                });
    }

    public boolean isTokenValid(String token){
        String tokenInCache = redisTemplate.opsForValue().get(token);
        if(tokenInCache != null && tokenInCache.equals(token)){
            return true;
        } 
        try {
            jwtDecoder.decode(token).block();
            redisTemplate.opsForValue().setIfAbsent(token, token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * Writes a JSON 401 response directly back to the client and completes the reactive chain.
     * No downstream filter or service is called after this — the response ends here.
     *
     * DataBuffer is the WebFlux equivalent of a byte array — required because the gateway
     * is non-blocking and cannot use OutputStream directly.
     */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        byte[] body = ("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}").getBytes();
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * Order -100 means this filter runs before Spring Cloud Gateway's built-in routing
     * filter (order 0) and load-balancer filter (order 1).
     *
     * If this ran after routing, the request would already be forwarded to the downstream
     * service before we checked the token — the whole point of the gateway would be lost.
     *
     * RateLimitFilter uses order -99 so it always runs after AuthFilter has injected
     * X-User-Id (the rate limiter needs it to key per user, not just per IP).
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
