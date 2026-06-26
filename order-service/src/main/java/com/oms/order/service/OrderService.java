package com.oms.order.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.order.domain.LineItem;
import com.oms.order.domain.Order;
import com.oms.order.domain.OrderEvent;
import com.oms.order.domain.OrderStatus;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.dto.CreateOrderRequest;
import com.oms.order.dto.CustomerOrdersResponse;
import com.oms.order.cache.OrderCacheManager;
import com.oms.order.repository.OrderEventRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.exception.OrderAccessDeniedException;
import com.oms.order.exception.OrderNotFoundException;
import com.oms.order.repository.OutboxEventRepository;
import com.oms.shared.event.InvoiceGeneratedEvent;
import com.oms.shared.event.OrderCreatedEvent;
import com.oms.shared.event.OrderLineItem;
import com.oms.shared.event.OrderStatusChangedEvent;
import com.oms.shared.event.PaymentConfirmedEvent;
import com.oms.shared.event.PaymentFailedEvent;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderEventRepository orderEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final DocumentStoreService documentStoreService;
    private final RedisTemplate<String, Order> redisTemplate;
    private final OrderCacheManager orderCacheManager;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orderRepository,
            OrderEventRepository orderEventRepository,
            OutboxEventRepository outboxEventRepository,
            DocumentStoreService documentStoreService,
            RedisTemplate<String, Order> redisTemplate,
            OrderCacheManager orderCacheManager,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.orderEventRepository = orderEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.documentStoreService = documentStoreService;
        this.redisTemplate = redisTemplate;
        this.orderCacheManager = orderCacheManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Order createOrder(String idempotencyKey, CreateOrderRequest request) {

        String redisKey = "idempotency:order:" + idempotencyKey;

        Order cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            return cached;
        }

        List<LineItem> lineItems = request.getLineItems().stream()
                .map(item -> LineItem.builder()
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .sku(item.getSku())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .toList();

        BigDecimal totalAmount = lineItems.stream()
                .map(LineItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .status(OrderStatus.PENDING)
                .lineItems(lineItems)
                .totalAmount(totalAmount)
                .shippingName(request.getShippingName())
                .shippingLine1(request.getShippingLine1())
                .shippingCity(request.getShippingCity())
                .shippingState(request.getShippingState())
                .shippingZip(request.getShippingZip())
                .shippingCountry(request.getShippingCountry())
                .notes(request.getNotes())
                .build();

        lineItems.forEach(li -> li.setOrder(order));

        Order saved = orderRepository.save(order);

        orderEventRepository.save(OrderEvent.builder()
                .orderId(saved.getId())
                .oldStatus(null)
                .newStatus(OrderStatus.PENDING)
                .actor(request.getCustomerId().toString())
                .build());

        outboxEventRepository.save(buildOrderCreatedOutboxEvent(saved));

        try {
            redisTemplate.opsForValue().set(redisKey, saved, Duration.ofHours(24));
        } catch (Exception e) {
            // Cache failure must not roll back the order — Redis is best-effort here
            log.warn("Failed to cache order {} in Redis: {}", saved.getId(), e.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Order getOrderById(UUID orderId) {
        // L1 JVM → L2 Redis → L3 Database — each tier tried in order
        return orderCacheManager.getOrder(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public CustomerOrdersResponse getOrders(UUID customerId) {
        List<OrderStatus> pastStatuses = List.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.REFUNDED);

        List<Order> pastOrders = orderRepository
                .findByCustomerIdAndStatusInOrderByCreatedAtAsc(customerId, pastStatuses);

        List<Order> currentOrders = orderRepository
                .findByCustomerIdAndStatusNotInOrderByCreatedAtAsc(customerId, pastStatuses);

        return CustomerOrdersResponse.builder()
                .currentOrders(currentOrders)
                .pastOrders(pastOrders)
                .build();
    }

    @Transactional
    public Order cancelOrder(UUID orderId, UUID customerId) {
        Order order = orderCacheManager.getOrder(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        String auditNote = order.getNotes().trim();

        if (!order.getCustomerId().equals(customerId)) {
            throw new OrderAccessDeniedException(orderId);
        }

        OrderStatus previous = order.getStatus();
        order.transitionTo(OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);

        orderEventRepository.save(OrderEvent.builder()
                .orderId(orderId)
                .oldStatus(previous)
                .newStatus(OrderStatus.CANCELLED)
                .actor(customerId.toString())
                .build());

        outboxEventRepository.save(buildOutboxEvent(orderId, "OrderStatusChanged",
                OrderStatusChangedEvent.builder()
                        .orderId(orderId)
                        .customerId(customerId)
                        .oldStatus(previous.name())
                        .newStatus(OrderStatus.CANCELLED.name())
                        .build()));

        // invalidate all cache tiers — order status changed, stale data must not be served
        orderCacheManager.invalidate(orderId);

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Order> getStaffOrders(OrderStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        if (status != null) {
            return orderRepository.findByStatus(status, pageable);
        }
        return orderRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderEvent> getOrderEvents(UUID orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return orderEventRepository.findByOrderIdOrderByOccurredAtAsc(orderId);
    }

    @Transactional
    public void handlePaymentConfirmed(PaymentConfirmedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(event.getOrderId()));

        OrderStatus previous = order.getStatus();
        order.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        orderEventRepository.save(OrderEvent.builder()
                .orderId(order.getId())
                .oldStatus(previous)
                .newStatus(OrderStatus.CONFIRMED)
                .actor("payment-service")
                .build());

        // claim check: store full order details as invoice document, put only the reference in Kafka
        String invoiceContent = buildInvoiceContent(order, event.getPaymentId());
        UUID documentId = documentStoreService.store("INVOICE", invoiceContent);

        outboxEventRepository.save(buildOutboxEvent(order.getId(), "OrderStatusChanged",
                OrderStatusChangedEvent.builder()
                        .orderId(order.getId())
                        .customerId(order.getCustomerId())
                        .oldStatus(previous.name())
                        .newStatus(OrderStatus.CONFIRMED.name())
                        .build()));

        outboxEventRepository.save(buildOutboxEvent(order.getId(), "InvoiceGenerated",
                InvoiceGeneratedEvent.builder()
                        .orderId(order.getId())
                        .customerId(order.getCustomerId())
                        .documentId(documentId)
                        .build()));
    }

    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(event.getOrderId()));

        OrderStatus previous = order.getStatus();
        order.transitionTo(OrderStatus.PAYMENT_FAILED);
        orderRepository.save(order);

        orderEventRepository.save(OrderEvent.builder()
                .orderId(order.getId())
                .oldStatus(previous)
                .newStatus(OrderStatus.PAYMENT_FAILED)
                .actor("payment-service")
                .build());

        outboxEventRepository.save(buildOutboxEvent(order.getId(), "OrderStatusChanged",
                OrderStatusChangedEvent.builder()
                        .orderId(order.getId())
                        .customerId(order.getCustomerId())
                        .oldStatus(previous.name())
                        .newStatus(OrderStatus.PAYMENT_FAILED.name())
                        .build()));
    }

    private String buildInvoiceContent(Order order, UUID paymentId) {
        try {
            // Map.of() rejects null values — shipping fields are optional so use HashMap
            java.util.Map<String, Object> invoice = new java.util.LinkedHashMap<>();
            invoice.put("orderId",      order.getId());
            invoice.put("customerId",   order.getCustomerId());
            invoice.put("paymentId",    paymentId);
            invoice.put("totalAmount",  order.getTotalAmount());
            invoice.put("currency",     order.getCurrency());
            invoice.put("lineItems",    order.getLineItems());
            invoice.put("shippingName", order.getShippingName());
            invoice.put("shippingLine1",order.getShippingLine1());
            invoice.put("shippingCity", order.getShippingCity());
            invoice.put("confirmedAt",  java.time.Instant.now());
            return objectMapper.writeValueAsString(invoice);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build invoice for order " + order.getId(), e);
        }
    }

    private OutboxEvent buildOutboxEvent(UUID aggregateId, String eventType, Object payload) {
        try {
            return OutboxEvent.builder()
                    .aggregateType("Order")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    // Capture the correlation ID from MDC now, while we are still on the
                    // HTTP request thread. The OutboxPoller runs on a background thread with
                    // no MDC, so it cannot read this later — we must save it to the DB here.
                    .correlationId(MDC.get("correlationId"))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize " + eventType + " for order " + aggregateId, e);
        }
    }

    private OutboxEvent buildOrderCreatedOutboxEvent(Order order) {
        List<OrderLineItem> items = order.getLineItems().stream()
                .map(item -> OrderLineItem.builder()
                        .productId(item.getProductId())
                        .warehouseId("WH-01")
                        .quantity(item.getQuantity())
                        .build())
                .toList();

        OrderCreatedEvent payload = OrderCreatedEvent.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .items(items)
                .build();

        try {
            return OutboxEvent.builder()
                    .aggregateType("Order")
                    .aggregateId(order.getId())
                    .eventType("OrderCreated")
                    .payload(objectMapper.writeValueAsString(payload))
                    .correlationId(MDC.get("correlationId"))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OrderCreatedEvent for order " + order.getId(), e);
        }
    }
}
