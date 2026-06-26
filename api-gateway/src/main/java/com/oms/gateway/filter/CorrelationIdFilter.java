package com.oms.gateway.filter;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Assigns a unique ID to every request that flows through the gateway.
 *
 * Problem it solves:
 *   A single user action (e.g. "place order") triggers calls across multiple services:
 *   order-service → inventory-service → payment-service → fulfillment-service.
 *   Without a shared ID, log lines from each service are impossible to connect.
 *   You would not know which inventory deduction belongs to which order.
 *
 * How it works:
 *   1. If the client already sent an X-Correlation-Id (e.g. from a mobile app), reuse it.
 *   2. Otherwise generate a new UUID.
 *   3. Inject it as a request header so every downstream service receives it.
 *   4. Echo it back on the response so the client can reference it when reporting bugs.
 *   5. Put it in MDC so every log line in the gateway itself includes it automatically.
 *
 * Order -200 — runs before AuthFilter (-100) so the ID is present in all filter logs.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    // Standard header name used across the industry (also seen as X-Request-Id)
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // Step 1: Decide the correlation ID.
        // If the caller (mobile app, frontend, another service) already set one, honour it.
        // This allows end-to-end tracing across system boundaries — the same ID that the
        // mobile app generated can appear in every backend service's logs.
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Capture in a final variable for use inside the lambda below
        final String finalCorrelationId = correlationId;

        // Step 2: Put the ID into SLF4J MDC (Mapped Diagnostic Context).
        // MDC is a per-thread key-value store that Logback/Log4j automatically appends
        // to every log line. After this call, every log.info/warn/error in this filter
        // and in downstream code on the same thread will include correlationId=<uuid>.
        // Example log output:
        //   2024-06-12 INFO AuthFilter correlationId=abc-123 userId=xyz path=/api/v1/orders
        MDC.put("correlationId", finalCorrelationId);

        log.debug("Assigned correlationId={}", finalCorrelationId);

        // Step 3: Inject the ID into the outgoing request headers so every downstream
        // service (order-service, inventory-service, etc.) receives it.
        // Each service should forward it in their own outgoing calls for full chain tracing.
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        // Step 4: Add the ID to the response headers so the client receives it back.
        // When a user reports a bug ("my order failed at 3pm"), they can send this ID
        // and you can instantly find every log line related to that exact request.
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().add(CORRELATION_ID_HEADER, finalCorrelationId);

        // Step 5: Continue to the next filter with the modified request.
        // .doFinally() cleans up MDC after the reactive chain completes (success or error).
        // Without this, MDC entries from one request could leak into the next request
        // on the same thread (thread reuse is common in reactive thread pools).
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signalType -> MDC.remove("correlationId"));
    }

    // Order -200 — lower than AuthFilter (-100) so it runs first.
    // This ensures the correlationId is in MDC before any other filter logs anything.
    @Override
    public int getOrder() {
        return -200;
    }
}
