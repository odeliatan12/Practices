# OMS-210 — Orders Stuck After Kafka Outage — Need Event Replay

| Field       | Value                                         |
|-------------|-----------------------------------------------|
| Priority    | P1 — Critical                                 |
| Reporter    | Backend Team — post-incident recovery         |
| Assignee    | Unassigned                                    |
| Created     | 2026-06-26 21:00 SGT                          |
| Environment | Production                                    |
| Service     | order-service, payment-service                |

---

## Customer Report

> "My order has been sitting on 'Confirmed' since yesterday afternoon. I spoke
> to your agent and they said there was a system issue. When will my order be
> processed? I need to know if my payment will go through."
>
> — Customer ticket #49445, submitted 20:40 SGT

This is a post-incident recovery ticket. A 45-minute Kafka outage yesterday
(17:00–17:45 SGT) caused all `OrderCreated` events published during that window
to be dead-lettered. 127 orders are now stuck in `CONFIRMED` with no payment
initiated.

The Kafka cluster is now healthy. We need to replay those events safely.

---

## What We Know

- Kafka outage occurred 17:00–17:45 SGT (now resolved)
- 127 orders created during the outage window have dead-lettered events
  in the `outbox_events` table
- Events were not lost — they are in the DB with `dead_lettered_at` set
- These orders need to be replayed but we must not double-process orders
  that may have partially succeeded
- payment-service has been running normally since 17:45 SGT

---

## The Challenge

Replaying events is not as simple as marking them for retry. You need to:

1. Identify which events are safe to replay (not already processed)
2. Replay in the correct order (by `created_at`) to avoid ordering violations
3. Not replay events for orders that were already manually resolved by support
4. Monitor the replay to confirm each event processes successfully

---

## Debugging Steps (Engineering)

```bash
# 1. Count how many events are dead-lettered
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT COUNT(*), event_type
   FROM outbox_events
   WHERE dead_lettered_at IS NOT NULL
   GROUP BY event_type;"

# 2. Check the time window of affected events
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT MIN(created_at), MAX(created_at)
   FROM outbox_events
   WHERE dead_lettered_at IS NOT NULL;"

# 3. Check if any affected orders were already manually resolved
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT o.id, o.status, oe.dead_lettered_at
   FROM orders o
   JOIN outbox_events oe ON oe.aggregate_id = o.id::text
   WHERE oe.dead_lettered_at IS NOT NULL
   AND o.status NOT IN ('PENDING', 'CONFIRMED')
   LIMIT 10;"
-- These are already resolved — do NOT replay

# 4. Identify safe-to-replay events (still stuck in CONFIRMED)
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT oe.id, oe.event_type, o.status, oe.created_at
   FROM outbox_events oe
   JOIN orders o ON o.id::text = oe.aggregate_id
   WHERE oe.dead_lettered_at IS NOT NULL
   AND o.status IN ('PENDING', 'CONFIRMED')
   ORDER BY oe.created_at ASC
   LIMIT 10;"

# 5. Verify Kafka is healthy before replaying
docker exec oms-kafka kafka-broker-api-versions \
  --bootstrap-server localhost:9092

docker exec oms-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe --topic orders
```

---

## Replay Procedure

```bash
# Reset dead_lettered_at and retry_count to make OutboxPoller pick them up again
# DO THIS ONLY FOR ORDERS STILL IN CONFIRMED/PENDING STATUS

docker exec oms-postgres psql -U oms -d orders_db -c \
  "UPDATE outbox_events
   SET dead_lettered_at = NULL,
       retry_count = 0,
       published_at = NULL
   WHERE id IN (
     SELECT oe.id
     FROM outbox_events oe
     JOIN orders o ON o.id::text = oe.aggregate_id
     WHERE oe.dead_lettered_at IS NOT NULL
     AND o.status IN ('PENDING', 'CONFIRMED')
   )
   RETURNING id, event_type;"

# Watch OutboxPoller pick up and process the events
docker logs oms-order-service -f 2>&1 | grep -i "outbox\|publish"

# Verify events are published (published_at is now set)
docker exec oms-postgres psql -U oms -d orders_db -c \
  "SELECT id, published_at, retry_count
   FROM outbox_events
   WHERE dead_lettered_at IS NULL
   AND published_at IS NOT NULL
   ORDER BY published_at DESC
   LIMIT 10;"
```

---

## Risk Checklist Before Replay

- [ ] Confirm Kafka cluster is healthy (broker API versions responds)
- [ ] Confirm payment-service consumer group is active and lag is 0
- [ ] Confirm `bootstrap-servers` config is correct (no port mismatch)
- [ ] Cross-check affected order IDs against support tickets — exclude manually resolved
- [ ] Run replay on 5 events first, confirm processed before doing all 127
- [ ] Have rollback plan: if replay causes issues, set `dead_lettered_at` back

---

## Impact

- 127 customers awaiting payment confirmation
- Orders not fulfilled — warehouse has not been notified
- Customer support handling 127 inbound queries

---

## Acceptance Criteria

- [ ] All 127 affected orders have `published_at` set in `outbox_events`
- [ ] payment-service confirms receipt and processing of all 127 events
- [ ] Order statuses updated beyond `CONFIRMED` within 10 minutes of replay
- [ ] Zero duplicate payments as a result of the replay
- [ ] Post-mortem completed and dead-letter alerting added for future outages
