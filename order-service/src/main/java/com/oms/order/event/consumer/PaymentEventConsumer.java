package com.oms.order.event.consumer;

import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.order.service.OrderService;
import com.oms.shared.event.PaymentConfirmedEvent;
import com.oms.shared.event.PaymentFailedEvent;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final String DEDUPE_PREFIX = "dedupe:payment:";
    private static final Duration DEDUPE_TTL = Duration.ofHours(24);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public PaymentEventConsumer(OrderService orderService, ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    @KafkaListener(topics = "payments", containerFactory = "stringKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        JsonNode root = objectMapper.readTree(record.value());
        String eventId = root.get("eventId").asText();
        String eventType = root.get("eventType").asText();

        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(DEDUPE_PREFIX + eventId, "1", DEDUPE_TTL);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("Duplicate payment event skipped eventId={} type={}", eventId, eventType);
            return;
        }

        log.info("Processing payment event eventId={} type={}", eventId, eventType);

        JsonNode payloadNode = root.get("payload");

        switch (eventType) {
            case "PaymentConfirmed" -> {
                PaymentConfirmedEvent event = objectMapper.treeToValue(payloadNode, PaymentConfirmedEvent.class);
                orderService.handlePaymentConfirmed(event);
            }
            case "PaymentFailed" -> {
                PaymentFailedEvent event = objectMapper.treeToValue(payloadNode, PaymentFailedEvent.class);
                orderService.handlePaymentFailed(event);
            }
            default -> log.warn("Unknown payment event type={} eventId={}", eventType, eventId);
        }
    }
}
