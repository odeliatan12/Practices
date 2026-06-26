package com.oms.order.filter;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the X-Correlation-Id header injected by the API Gateway and puts it
 * into SLF4J MDC so every log line in this service automatically includes it.
 *
 * Why OncePerRequestFilter?
 *   Spring may call filters multiple times per request in some edge cases
 *   (e.g. async dispatch, error forwarding). OncePerRequestFilter guarantees
 *   this logic runs exactly once per HTTP request, preventing duplicate MDC entries.
 *
 * Where the ID comes from:
 *   The API Gateway's CorrelationIdFilter stamps X-Correlation-Id on every request
 *   before forwarding it here. This service simply reads it — it does not generate one.
 *   If somehow a request arrives without the header (e.g. direct internal call, test),
 *   a new UUID is generated as a fallback so logs are never missing the field.
 *
 * What "MDC" means in plain English:
 *   MDC is a per-thread sticky note. Once you write correlationId=abc onto it,
 *   every log.info/warn/error call on that thread automatically appends it to the
 *   log line — you do not pass the ID manually into every method.
 *   See logback-spring.xml for the pattern that prints it.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    // Must match the header name used by the gateway's CorrelationIdFilter
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    // The MDC key — matches the %X{correlationId} pattern in logback-spring.xml
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Step 1: Read the ID stamped by the gateway.
        // Fall back to a new UUID if the request arrived without one (e.g. direct call in tests).
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Step 2: Write to MDC.
        // From this point, every log line on this thread will include:
        //   correlationId=abc-123
        MDC.put(MDC_KEY, correlationId);

        // Step 3: Echo the ID back on the response header.
        // The client receives the same ID it sent (or the one we generated).
        // Useful when the mobile app needs to correlate its own logs with server logs.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            // Step 4: Hand off to the next filter in the chain (then the controller).
            filterChain.doFilter(request, response);
        } finally {
            // Step 5: Always clean up MDC after the request completes — success or exception.
            // Servlet containers reuse threads (thread pool). Without this, the next request
            // on the same thread would inherit the previous request's correlationId.
            MDC.remove(MDC_KEY);
        }
    }
}
