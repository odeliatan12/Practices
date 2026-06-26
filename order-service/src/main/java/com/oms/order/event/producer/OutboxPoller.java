package com.oms.order.event.producer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.repository.OutboxEventRepository;
import com.oms.shared.event.DomainEvent;
import com.oms.shared.event.InvoiceGeneratedEvent;
import com.oms.shared.event.OrderCreatedEvent;
import com.oms.shared.event.OrderStatusChangedEvent;

import jakarta.annotation.PreDestroy;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int MAX_RETRIES = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, DomainEvent<?>> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // ExecutorService = the thread pool (the kitchen with chefs).
    // It controls HOW MANY threads exist and manages reusing them.
    // Fixed at 5 means at most 5 events publish simultaneously — prevents overwhelming Kafka or the DB.
    // Created once when the class starts, lives for the lifetime of the application.
    // Without this, CompletableFuture would use the default ForkJoinPool which is shared
    // across the entire JVM and cannot be controlled or shut down cleanly.
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    public OutboxPoller(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, DomainEvent<?>> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void poll() {
        // FOR UPDATE SKIP LOCKED: safe under multiple poller instances — each grabs its own rows
        List<OutboxEvent> pending = outboxEventRepository.findUnpublishedForUpdate();
        AtomicInteger published = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // .stream() not .parallelStream() — runAsync already puts work on the executor thread pool.
        // parallelStream() would use ForkJoinPool (uncontrolled). runAsync(executor) uses our fixed pool.
        List<CompletableFuture<Void>> futures = pending.stream()
                .map(event -> CompletableFuture
                    // CompletableFuture = the ticket tracking this one task (the order slip).
                    // runAsync() hands the publish task to a free thread in the executor pool.
                    // Returns immediately with a ticket — does NOT wait for publish() to finish.
                    // The second argument tells it to use OUR executor, not the default ForkJoinPool.
                    .runAsync(() -> publish(event, published, failed), executor)

                    // .exceptionally() = the safety net for unexpected exceptions that escape publish().
                    // publish() already handles its own errors internally (retry, dead-letter).
                    // This catches anything truly unexpected so one bad event does not kill the future,
                    // which would prevent allOf().join() from completing cleanly.
                    // Returning null is required because CompletableFuture<Void> has no return value.
                    .exceptionally(ex -> {
                        log.error("Unexpected error processing outbox event id={}", event.getId(), ex);
                        return null;
                    })
                ).toList();

        // allOf() combines all individual tickets into one super-ticket that is complete
        // only when EVERY individual ticket is complete (success or handled failure).
        // .join() blocks the scheduler thread here until that super-ticket is done.
        // Without this, poll() would return immediately and log "0 published, 0 failed"
        // because no thread has had time to finish yet.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Outbox poll completed: {} published, {} failed", published.get(), failed.get());
    }

    private void publish(OutboxEvent event, AtomicInteger published, AtomicInteger failed) {
        // The OutboxPoller runs on a background scheduler thread that has no MDC of its own.
        // We restore the correlation ID from the OutboxEvent (captured when the HTTP request
        // created the event) so log lines from this background thread are traceable.
        String correlationId = event.getCorrelationId();
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }

        try {
            DomainEvent<?> kafkaEvent = toKafkaEvent(event);

            // Build a ProducerRecord instead of using the simple kafkaTemplate.send(topic, key, value)
            // so we can attach Kafka headers. Headers travel with the message to the consumer.
            // The consumer (inventory-service, payment-service) reads the header and puts it in
            // its own MDC — linking their log lines to the original HTTP request.
            ProducerRecord<String, DomainEvent<?>> record = new ProducerRecord<>(
                    "orders",
                    null,                                        // partition — let Kafka decide
                    event.getAggregateId().toString(),           // message key (orderId)
                    kafkaEvent                                   // message value
            );

            // Attach the correlation ID as a Kafka header if present
            if (correlationId != null) {
                record.headers().add(new RecordHeader("X-Correlation-Id", correlationId.getBytes()));
            }

            // .get() blocks until the broker acknowledges the write — only then mark as published.
            // Without .get(), setPublishedAt() would run before Kafka confirms, silently losing events
            // if the broker rejects the message after the DB transaction commits.
            // Timeout prevents threads from blocking indefinitely if Kafka is slow.
            // Without it, all 5 pool threads can be stuck waiting → new events starve.
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);

            event.setPublishedAt(Instant.now());
            published.incrementAndGet();
        } catch (Exception ex) {
            failed.incrementAndGet();
            event.setRetryCount(event.getRetryCount() + 1);

            if (event.getRetryCount() >= MAX_RETRIES) {
                // Livelock fix: stop retrying — route to dead letter topic for manual inspection.
                // Without this, the same event is picked up every poll cycle forever.
                event.setDeadLetteredAt(Instant.now());
                log.error("Event id={} dead-lettered after {} retries", event.getId(), MAX_RETRIES, ex);
            } else {
                log.warn("Failed to publish event id={} (attempt {}/{})",
                        event.getId(), event.getRetryCount(), MAX_RETRIES, ex);
            }
        } finally {
            // Always clean up MDC — the executor thread pool reuses threads, so the next
            // event processed by this thread must not inherit this event's correlation ID.
            MDC.remove("correlationId");
        }
    }

    private DomainEvent<?> toKafkaEvent(OutboxEvent outboxEvent) throws Exception {
        Object payload = switch (outboxEvent.getEventType()) {
            case "OrderCreated"       -> objectMapper.readValue(outboxEvent.getPayload(), OrderCreatedEvent.class);
            case "OrderStatusChanged" -> objectMapper.readValue(outboxEvent.getPayload(), OrderStatusChangedEvent.class);
            case "InvoiceGenerated"   -> objectMapper.readValue(outboxEvent.getPayload(), InvoiceGeneratedEvent.class);
            default -> throw new IllegalArgumentException("Unknown event type: " + outboxEvent.getEventType());
        };

        return DomainEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(outboxEvent.getEventType())
                .aggregateType(outboxEvent.getAggregateType())
                .aggregateId(outboxEvent.getAggregateId())
                .source("order-service")
                .occurredAt(outboxEvent.getCreatedAt())
                .version(1)
                .payload(payload)
                .build();
    }

    // @PreDestroy = Spring calls this method when the application is shutting down.
    // ExecutorService must be shut down manually — it will not stop on its own.
    // Without this, the JVM cannot exit because live threads keep it alive.
    @PreDestroy
    public void shutdown() {
        // Step 1: stop accepting new tasks, let currently running tasks finish
        executor.shutdown();
        try {
            // Step 2: wait up to 30 seconds for running tasks to complete gracefully
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                // Step 3: if tasks are still running after 30s, force-stop them
                // This is the last resort — in-flight Kafka sends may be lost
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            // Restore the interrupted flag so the calling thread knows it was interrupted
            Thread.currentThread().interrupt();
        }
    }
}
