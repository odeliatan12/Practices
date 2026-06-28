package com.oms.inventory.consumer;

import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.shared.event.OrderCreatedEvent;
import com.oms.shared.event.OrderLineItem;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final String DEDUPE_PREFIX = "dedupe:inventory:order:";
    private static final Duration DEDUPE_TTL = Duration.ofHours(24);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public OrderEventConsumer(ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    @KafkaListener(
            topics = "orders",
            groupId = "inventory-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        JsonNode root = objectMapper.readTree(record.value());
        String eventId = root.path("eventId").asText();
        String eventType = root.path("eventType").asText();

        if (!"OrderCreated".equals(eventType)) {
            log.debug("Skipping non-OrderCreated event type={}", eventType);
            return;
        }

        // Idempotency check — prevent double reservation if event is redelivered
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(DEDUPE_PREFIX + eventId, "1", DEDUPE_TTL);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("Duplicate OrderCreated event skipped eventId={}", eventId);
            return;
        }

        OrderCreatedEvent event = objectMapper.treeToValue(root.path("payload"), OrderCreatedEvent.class);

        log.info("Reserving stock for orderId={} — {} line items",
                event.getOrderId(), event.getItems().size());

        for (OrderLineItem item : event.getItems()) {
            log.info("Reserving {} units of productId={} in warehouseId={}",
                    item.getQuantity(), item.getProductId(), item.getWarehouseId());
            // In a real system: update stock_reservations table
        }

        log.info("Stock reserved successfully for orderId={}", event.getOrderId());
    }
}
