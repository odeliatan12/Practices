package com.oms.gateway.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Handles requests that the Circuit Breaker has redirected after detecting
 * that a downstream service is unhealthy.
 *
 * How the Circuit Breaker routes here:
 *   In application.yml each route has a CircuitBreaker filter configured with
 *   fallbackUri: forward:/fallback/<service-name>
 *   When the circuit opens, Spring Cloud Gateway forwards the original request
 *   to that URI internally — no redirect visible to the client.
 *
 * Circuit Breaker states:
 *   CLOSED     → normal operation, all requests pass through to the real service
 *   OPEN       → too many failures detected; requests are instantly rejected and
 *                redirected here (fail fast — no waiting for a timeout)
 *   HALF_OPEN  → after a cooldown period, one probe request is allowed through;
 *                if it succeeds the circuit closes again; if it fails it reopens
 *
 * Why fail fast matters at Shopee/Lazada scale:
 *   If inventory-service is down and we keep forwarding requests, threads pile up
 *   waiting for responses that never come. Memory fills, the gateway slows, and
 *   eventually every service is affected — a cascade failure. Failing fast immediately
 *   protects the gateway and gives inventory-service time to recover.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    /**
     * Fallback for the order-service.
     * Mapped to the fallbackUri in the order-service circuit breaker config.
     *
     * Returns 503 Service Unavailable — this tells the client to retry later
     * rather than 500 Internal Server Error which implies a bug.
     */
    @RequestMapping("/fallback/order")
    public Mono<Map<String, String>> orderFallback(ServerWebExchange exchange) {
        log.warn("Circuit breaker open for order-service — returning fallback response");
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(Map.of(
                "error",   "service_unavailable",
                "service", "order-service",
                // Tell the client what to do next instead of just showing an error
                "message", "Order service is temporarily unavailable. Please try again in a moment.",
                // Include the correlation ID so the client can quote it when reporting issues
                "correlationId", getCorrelationId(exchange)
        ));
    }

    /**
     * Fallback for inventory-service.
     * Common cause: stock check during flash sales overwhelms the service.
     */
    @RequestMapping("/fallback/inventory")
    public Mono<Map<String, String>> inventoryFallback(ServerWebExchange exchange) {
        log.warn("Circuit breaker open for inventory-service — returning fallback response");
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(Map.of(
                "error",   "service_unavailable",
                "service", "inventory-service",
                "message", "Inventory service is temporarily unavailable. Please try again in a moment.",
                "correlationId", getCorrelationId(exchange)
        ));
    }

    /**
     * Fallback for payment-service.
     * Extra caution here: never tell the user their payment "failed" — it may
     * still be in-flight. Tell them to check their order status instead.
     */
    @RequestMapping("/fallback/payment")
    public Mono<Map<String, String>> paymentFallback(ServerWebExchange exchange) {
        log.warn("Circuit breaker open for payment-service — returning fallback response");
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(Map.of(
                "error",   "service_unavailable",
                "service", "payment-service",
                "message", "Payment service is temporarily unavailable. Do not retry — check your order status to confirm payment.",
                "correlationId", getCorrelationId(exchange)
        ));
    }

    /**
     * Fallback for fulfillment-service.
     * Fulfillment is less time-sensitive than payment — shipping can be queued.
     */
    @RequestMapping("/fallback/fulfillment")
    public Mono<Map<String, String>> fulfillmentFallback(ServerWebExchange exchange) {
        log.warn("Circuit breaker open for fulfillment-service — returning fallback response");
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(Map.of(
                "error",   "service_unavailable",
                "service", "fulfillment-service",
                "message", "Fulfillment service is temporarily unavailable. Your order is confirmed — shipping details will follow.",
                "correlationId", getCorrelationId(exchange)
        ));
    }

    /**
     * Reads the correlation ID that CorrelationIdFilter injected on the way in.
     * Including it in the fallback response lets the client quote it when reporting the issue.
     */
    private String getCorrelationId(ServerWebExchange exchange) {
        String id = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        return id != null ? id : "unknown";
    }
}
