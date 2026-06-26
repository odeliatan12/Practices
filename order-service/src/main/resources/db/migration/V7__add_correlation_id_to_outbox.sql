-- Stores the correlation ID alongside the outbox event so the OutboxPoller
-- can attach it as a Kafka message header when publishing.
-- This lets consuming services (inventory, payment) read it and put it in their
-- own MDC — linking log lines across all services for a single user request.
--
-- Nullable because existing rows have no ID, and direct DB writes in tests may omit it.
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(64);
