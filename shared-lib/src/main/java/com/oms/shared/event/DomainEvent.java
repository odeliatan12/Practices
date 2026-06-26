package com.oms.shared.event;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DomainEvent<T> {

    private UUID eventId;
    private String eventType;
    private String aggregateType;
    private UUID aggregateId;
    private String source;
    private Instant occurredAt;
    private int version;
    private T payload;
    
}
