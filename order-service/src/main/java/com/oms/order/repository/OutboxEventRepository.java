package com.oms.order.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.oms.order.domain.OutboxEvent;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // SKIP LOCKED: if another poller instance already has a row locked, skip it instead of blocking.
    // dead_lettered_at IS NULL: exclude events that have exhausted retries — stops the livelock.
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND dead_lettered_at IS NULL
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate();
}
