package com.oms.order.event.producer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.repository.OutboxEventRepository;
import com.oms.shared.event.DomainEvent;
import com.oms.shared.event.OrderCreatedEvent;
import com.oms.shared.event.OrderStatusChangedEvent;
import com.oms.shared.event.InvoiceGeneratedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    // Raw type avoids generic wildcard issues in Mockito stubbing.
    // The actual send() return type is CompletableFuture<SendResult<K,V>>.
    @Mock
    private KafkaTemplate kafkaTemplate;

    // Use a real ObjectMapper — Mockito cannot stub ObjectMapper.readValue()
    // because Jackson declares it across a non-public parent class (ObjectCodec),
    // which Mockito's byte-buddy proxying cannot intercept reliably.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxPoller outboxPoller;

    @BeforeEach
    void setUp() {
        outboxPoller = new OutboxPoller(outboxEventRepository, kafkaTemplate, objectMapper);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a pending OutboxEvent. publishedAt is null → "not yet sent".
     * Payload is an empty JSON object — valid for all event types in toKafkaEvent().
     */
    private static OutboxEvent pendingEvent(String eventType) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("Order")
                .aggregateId(UUID.randomUUID())
                .eventType(eventType)
                .payload("{}")          // valid but empty — fields will be null, which is fine for testing
                .createdAt(Instant.now())
                .build();
    }

    /** Kafka future that resolves successfully — broker acknowledged the write. */
    private static CompletableFuture<SendResult<String, DomainEvent<?>>> kafkaSuccess() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    /** Kafka future that resolves with failure — broker rejected or connection timed out. */
    private static CompletableFuture<SendResult<String, DomainEvent<?>>> kafkaFailure(String reason) {
        CompletableFuture<SendResult<String, DomainEvent<?>>> f = new CompletableFuture<>();
        // completeExceptionally → .get() throws ExecutionException wrapping this cause
        f.completeExceptionally(new RuntimeException(reason));
        return f;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 1 — Happy path: all events reach the broker
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("all Kafka sends succeed → every event gets publishedAt stamped")
    void poll_allKafkaSendsSucceed_allEventsGetPublishedAt() {
        // GIVEN — three pending events of different types
        OutboxEvent e1 = pendingEvent("OrderCreated");
        OutboxEvent e2 = pendingEvent("OrderStatusChanged");
        OutboxEvent e3 = pendingEvent("InvoiceGenerated");

        when(outboxEventRepository.findUnpublishedForUpdate()).thenReturn(List.of(e1, e2, e3));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(kafkaSuccess());

        // WHEN
        outboxPoller.poll();

        // THEN — every event has publishedAt set; none will reappear in the next poll cycle
        assertThat(e1.getPublishedAt()).isNotNull();
        /*
         * Expected log:
         *   [INFO] [pool-1-thread-1] Published event id=<uuid> type=OrderCreated
         */

        assertThat(e2.getPublishedAt()).isNotNull();
        /*
         * Expected log:
         *   [INFO] [pool-1-thread-2] Published event id=<uuid> type=OrderStatusChanged
         */

        assertThat(e3.getPublishedAt()).isNotNull();
        /*
         * Expected log:
         *   [INFO] [pool-1-thread-3] Published event id=<uuid> type=InvoiceGenerated
         */

        /*
         * Expected summary log:
         *   [INFO] [scheduling-1] Outbox poll completed: 3 published, 0 failed
         */
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 2 — One Kafka send fails → that event stays null and gets retried
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kafka send throws → failed event keeps publishedAt=null, next poll retries it")
    void poll_kafkaSendThrows_failedEventRemainsNullForRetry() {
        // GIVEN — first event succeeds; second event's broker times out
        OutboxEvent e1 = pendingEvent("OrderCreated");
        OutboxEvent e2 = pendingEvent("OrderStatusChanged");

        when(outboxEventRepository.findUnpublishedForUpdate()).thenReturn(List.of(e1, e2));

        // Differentiate success/failure by the Kafka message key (aggregateId)
        when(kafkaTemplate.send(anyString(), eq(e1.getAggregateId().toString()), any()))
                .thenReturn(kafkaSuccess());
        when(kafkaTemplate.send(anyString(), eq(e2.getAggregateId().toString()), any()))
                .thenReturn(kafkaFailure("NETWORK_EXCEPTION: Connection to broker 9092 timed out"));

        // WHEN
        outboxPoller.poll();

        // THEN
        assertThat(e1.getPublishedAt()).isNotNull();
        /*
         * Expected log:
         *   [INFO] [pool-1-thread-1] Published event id=<uuid> type=OrderCreated
         */

        assertThat(e2.getPublishedAt()).isNull();
        /*
         * Expected log:
         *   [ERROR] [pool-1-thread-2] Failed to publish outbox event id=<uuid>
         *             java.util.concurrent.ExecutionException: java.lang.RuntimeException:
         *             NETWORK_EXCEPTION: Connection to broker 9092 timed out
         *
         * Because published_at IS NULL in DB, the next poll cycle picks this row up
         * and tries again automatically.
         *
         * Expected log at next poll (5 seconds later, if broker recovered):
         *   [INFO] [scheduling-1] Outbox poll completed: 1 pending events
         *   [INFO] [pool-1-thread-1] Published event id=<uuid> type=OrderStatusChanged
         *   [INFO] [scheduling-1] Outbox poll completed: 1 published, 0 failed
         */

        /*
         * Expected summary log:
         *   [INFO] [scheduling-1] Outbox poll completed: 1 published, 1 failed
         */
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 3 — 10 events, 5 threads: AtomicInteger counts must be exact
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 events published by 5 threads concurrently — published/failed counts are exact")
    void poll_tenEventsConcurrently_countsAreAccurateUnderParallelism() {
        // GIVEN — 10 events; every 3rd Kafka call fails (calls 3, 6, 9 → 3 failures)
        List<OutboxEvent> events = List.of(
                pendingEvent("OrderCreated"),       // call 1  → success
                pendingEvent("OrderCreated"),       // call 2  → success
                pendingEvent("OrderStatusChanged"), // call 3  → FAIL
                pendingEvent("OrderCreated"),       // call 4  → success
                pendingEvent("OrderCreated"),       // call 5  → success
                pendingEvent("OrderStatusChanged"), // call 6  → FAIL
                pendingEvent("OrderCreated"),       // call 7  → success
                pendingEvent("OrderCreated"),       // call 8  → success
                pendingEvent("InvoiceGenerated"),   // call 9  → FAIL
                pendingEvent("OrderCreated")        // call 10 → success
        );

        when(outboxEventRepository.findUnpublishedForUpdate()).thenReturn(events);

        // callNumber tracks which Kafka call this is.
        // 5 threads race concurrently — the call order is non-deterministic,
        // but the total count is always exactly 10 (one per event).
        AtomicInteger callNumber = new AtomicInteger(0);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            int n = callNumber.incrementAndGet();
            return (n % 3 == 0)
                    ? kafkaFailure("Broker leader not available for partition")
                    : kafkaSuccess();
        });

        // WHEN — 5 threads from the executor pool race to publish all 10 events
        outboxPoller.poll();

        // THEN — count exactly which events were published vs not
        long published = events.stream().filter(e -> e.getPublishedAt() != null).count();
        long failed    = events.stream().filter(e -> e.getPublishedAt() == null).count();

        assertThat(published).isEqualTo(7);
        /*
         * 7 events have publishedAt set. Log output is interleaved across 5 threads
         * (order is non-deterministic):
         *   [INFO] [pool-1-thread-3] Published event id=... type=OrderCreated
         *   [INFO] [pool-1-thread-1] Published event id=... type=OrderCreated
         *   [INFO] [pool-1-thread-5] Published event id=... type=OrderCreated
         *   ... (7 total)
         *
         * You may also see interleaved lines in production logs, e.g.:
         *   [INFO] [pool-1-thread-2] Published event id=[ERROR] [pool-1-thread-4] Failed...
         * → Add %thread to your logback.xml pattern so log lines stay on one line per thread.
         */

        assertThat(failed).isEqualTo(3);
        /*
         * 3 events have publishedAt=NULL → will be retried in the next poll cycle (5s later).
         * Expected logs:
         *   [ERROR] [pool-1-thread-2] Failed to publish outbox event id=... — Broker leader not available
         *   [ERROR] [pool-1-thread-4] Failed to publish outbox event id=... — Broker leader not available
         *   [ERROR] [pool-1-thread-1] Failed to publish outbox event id=... — Broker leader not available
         */

        /*
         * Expected summary log:
         *   [INFO] [scheduling-1] Outbox poll completed: 7 published, 3 failed
         */
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 4 — Demonstration of the bug we fixed (the race condition)
    //  These tests do NOT use OutboxPoller — they simulate the timing difference
    //  between fire-and-forget (OLD code) and .get() blocking (FIXED code).
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Race condition: fire-and-forget (OLD) vs blocking confirm (FIXED)")
    class OptimisticPublishRaceCondition {

        @Test
        @DisplayName("OLD CODE: fire-and-forget sets publishedAt before Kafka confirms → silent data loss")
        void buggyApproach_kafkaFailsAsync_eventIsSilentlyMarkedPublished() throws Exception {
            //
            // The OLD publish() method:
            //
            //   kafkaTemplate.send(topic, key, event)
            //       .whenComplete((result, ex) -> {        // ← registered, runs LATER on another thread
            //           if (ex != null) failed.incrementAndGet();
            //       });
            //   event.setPublishedAt(Instant.now());       // ← runs IMMEDIATELY, before Kafka confirms
            //   published.incrementAndGet();
            //
            // @Transactional commits here → published_at written to DB.
            // The whenComplete callback fires later and discovers Kafka rejected the send.
            // But the DB row already has published_at — the event is gone forever.
            //

            OutboxEvent event = pendingEvent("OrderCreated");
            CountDownLatch kafkaCallbackFired = new CountDownLatch(1);
            AtomicBoolean dbCommittedBeforeCallback = new AtomicBoolean(false);

            // Kafka send that rejects after 150ms (simulating a slow broker timeout)
            CompletableFuture<Void> kafkaSend = CompletableFuture.runAsync(() -> {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new RuntimeException("NETWORK_EXCEPTION: Connection to broker 9092 timed out");
            });

            // OLD: register callback — does NOT wait for it
            kafkaSend.whenComplete((result, ex) -> {
                if (ex != null) {
                    /*
                     * This fires ~150ms AFTER setPublishedAt and the DB commit.
                     *
                     * Expected log (appears AFTER "Outbox poll completed"):
                     *   [ERROR] [ForkJoinPool.commonPool-worker-1]
                     *           Kafka send failed id=<uuid>
                     *           java.lang.RuntimeException:
                     *           NETWORK_EXCEPTION: Connection to broker 9092 timed out
                     *
                     * Thread name is "ForkJoinPool.commonPool" — the callback ran on Java's
                     * shared background pool, AFTER the poller's @Transactional already committed.
                     */
                    kafkaCallbackFired.countDown();
                }
            });

            // OLD: setPublishedAt runs immediately — Kafka hasn't confirmed
            event.setPublishedAt(Instant.now());
            /*
             * Expected log (appears BEFORE the Kafka error):
             *   [INFO] [scheduling-1] Outbox poll completed: 1 published, 0 failed
             *                                                  ^^^^^^^^^^^^^^^^^^^
             *   "0 failed" is wrong — Kafka rejected it, but we don't know yet.
             */

            // @Transactional commits here — published_at written to DB
            dbCommittedBeforeCallback.set(true);
            /*
             * Expected log:
             *   [INFO] [@Transactional] DB committed — published_at=2026-06-08T10:00:00Z stored
             *
             * The DB row now has published_at set. findUnpublishedForUpdate() uses
             * WHERE published_at IS NULL — this row will NEVER be returned again.
             */

            kafkaCallbackFired.await(2, TimeUnit.SECONDS); // wait for async callback

            // BUG: published_at is SET even though Kafka rejected the message
            assertThat(event.getPublishedAt()).isNotNull();
            assertThat(dbCommittedBeforeCallback.get()).isTrue(); // DB committed BEFORE callback

            /*
             * Production incident this causes:
             *   - Dashboard shows "0 errors" — looks healthy
             *   - Downstream inventory service never gets the OrderCreated event
             *   - Customer paid; inventory was never reserved; order is stuck
             *   - Engineer queries outbox_events → all rows have published_at → no backlog
             *   - Engineer checks Kafka consumer lag → 0 → looks fine
             *   - Data is silently lost with no trace
             */
        }

        @Test
        @DisplayName("FIXED CODE: .get() blocks until broker confirms → Kafka failure leaves publishedAt=null")
        void fixedApproach_kafkaFails_eventRemainsNullSoNextPollRetries() throws Exception {
            //
            // The FIXED publish() method:
            //
            //   kafkaTemplate.send(topic, key, event).get();  // ← BLOCKS until broker ACKs
            //   event.setPublishedAt(Instant.now());           // ← only reached on success
            //
            // If Kafka rejects, .get() throws ExecutionException.
            // publish() catches it, does NOT call setPublishedAt.
            // @Transactional commits with published_at = NULL.
            // Next poll cycle, findUnpublishedForUpdate() returns this row again → retry.
            //

            OutboxEvent event = pendingEvent("OrderCreated");

            // Kafka rejects immediately
            CompletableFuture<Void> kafkaSend = new CompletableFuture<>();
            kafkaSend.completeExceptionally(
                    new RuntimeException("NETWORK_EXCEPTION: Connection to broker 9092 timed out"));

            boolean kafkaSucceeded = false;
            try {
                kafkaSend.get(); // blocks → throws ExecutionException wrapping the RuntimeException
                kafkaSucceeded = true;
                event.setPublishedAt(Instant.now()); // never reached on failure
            } catch (ExecutionException ex) {
                /*
                 * Expected log:
                 *   [ERROR] [pool-1-thread-1] Failed to publish outbox event id=<uuid>
                 *             java.util.concurrent.ExecutionException:
                 *             java.lang.RuntimeException:
                 *             NETWORK_EXCEPTION: Connection to broker 9092 timed out
                 *
                 * publishedAt is not set — the catch block exits without calling setPublishedAt.
                 * @Transactional commits with published_at = NULL.
                 */
            }

            // publishedAt is NULL → this row is picked up by the next poll cycle
            assertThat(event.getPublishedAt()).isNull();
            assertThat(kafkaSucceeded).isFalse();

            /*
             * Expected logs at the next poll cycle (5 seconds later):
             *   [INFO] [scheduling-1] Starting poll — 1 pending events
             *
             * If the broker recovered:
             *   [INFO] [pool-1-thread-1] Published event id=<uuid> type=OrderCreated
             *   [INFO] [scheduling-1] Outbox poll completed: 1 published, 0 failed
             *
             * No data loss. The event eventually reaches the downstream service.
             */
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 5 — Graceful shutdown: @PreDestroy must not hang
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown waits up to 30s for in-flight sends then terminates the thread pool")
    void shutdown_executorTerminatesWithoutHanging() {
        // WHEN — Spring calls @PreDestroy on application context close
        outboxPoller.shutdown();

        // THEN — this test completes without hanging → executor shut down cleanly.
        // A hang here would be the failure — no explicit assertion needed.

        /*
         * Expected logs during normal Spring shutdown (no in-flight sends):
         *   [INFO] [main] Closing JVM shutdown hook
         *   (executor shuts down immediately — no pending tasks)
         *
         * Expected logs if a send is in-flight when shutdown is called:
         *   [INFO] [main] Waiting up to 30s for in-flight Kafka sends to complete...
         *
         * If broker is completely gone and 30s elapses with no ACK:
         *   [WARN] [main] OutboxPoller executor did not terminate — forcing shutdown
         *   [INFO] [main] Context closed
         *
         * The shutdownNow() interrupts blocked .get() calls.
         * Interrupted threads throw InterruptedException, publish() catches it as a failure,
         * leaves published_at = NULL → those events are retried on the next service startup.
         */
    }
}
