package com.oms.gateway.exception;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * Catches any unhandled exception that escapes all filters and controllers.
 *
 * Problem it solves:
 *   Without this, Spring Boot returns its default Whitelabel error page — an HTML
 *   response with a full Java stack trace. This is wrong for two reasons:
 *     1. Our API clients expect JSON, not HTML.
 *     2. Stack traces leak internal class names, package paths, and framework versions
 *        to anyone who can read the response — a security risk in production.
 *
 * What it catches:
 *   - Redis connection failure (rate limiter cannot reach Redis)
 *   - Downstream service connection refused (circuit breaker misconfigured)
 *   - Any RuntimeException not caught by a filter or controller
 *   - 404 when no route matches the requested path
 *
 * Order @Order(-1):
 *   Spring Boot's default error handler is at order Ordered.LOWEST_PRECEDENCE.
 *   We use -1 to run just before it, overriding the default without interfering
 *   with Spring Security's error handling (which uses lower order numbers).
 */
@Component
@Order(-1)
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    // ObjectMapper serialises the error Map to JSON bytes
    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {

        // Determine the HTTP status code.
        // ResponseStatusException is thrown by Spring itself for known error conditions
        // (e.g. 404 no route found, 405 method not allowed).
        // All other exceptions are treated as 500 Internal Server Error.
        HttpStatus status;
        String errorCode;

        if (ex instanceof ResponseStatusException rse) {
            // Cast to get the specific status Spring chose (404, 405, etc.)
            status    = HttpStatus.resolve(rse.getStatusCode().value());
            errorCode = status != null ? toSnakeCase(status.getReasonPhrase()) : "error";
            if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        } else {
            status    = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "internal_server_error";
        }

        // Log the full stack trace internally so it appears in ELK/Grafana,
        // but never expose it in the response body sent to the client.
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst("X-Correlation-Id");
        log.error("Unhandled exception correlationId={} path={} status={}: {}",
                correlationId,
                exchange.getRequest().getURI().getPath(),
                status.value(),
                ex.getMessage(),
                ex);

        // Build a safe JSON error body — no stack trace, no internal class names.
        // correlationId is included so the client can quote it when raising a support ticket.
        Map<String, String> body = Map.of(
                "error",         errorCode,
                "message",       safeMessage(ex, status),
                "correlationId", correlationId != null ? correlationId : "unknown"
        );

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // Fallback if JSON serialisation itself fails — should never happen
            bytes = "{\"error\":\"internal_server_error\"}".getBytes();
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * Returns a message safe to show to the client.
     * For 5xx errors we never expose the real exception message — it may contain
     * SQL, internal hostnames, or configuration details.
     */
    private String safeMessage(Throwable ex, HttpStatus status) {
        if (status.is4xxClientError()) {
            // 4xx errors are caused by the client — tell them what they did wrong
            return ex.getMessage() != null ? ex.getMessage() : status.getReasonPhrase();
        }
        // 5xx errors are our fault — give a generic message only
        return "An unexpected error occurred. Please try again later.";
    }

    /** Converts "Not Found" → "not_found", "Internal Server Error" → "internal_server_error" */
    private String toSnakeCase(String phrase) {
        return phrase.toLowerCase().replace(" ", "_");
    }
}
