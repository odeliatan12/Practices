package com.oms.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.order.domain.Order;
import com.oms.order.domain.OrderStatus;
import com.oms.order.domain.OutboxEvent;
import com.oms.order.dto.CreateOrderRequest;
import com.oms.order.dto.LineItemRequest;
import com.oms.order.cache.OrderCacheManager;
import com.oms.order.exception.OrderAccessDeniedException;
import com.oms.order.exception.OrderNotFoundException;
import com.oms.order.repository.OrderEventRepository;
import com.oms.order.repository.OrderRepository;
import com.oms.order.repository.OutboxEventRepository;
import com.oms.shared.event.PaymentConfirmedEvent;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventRepository orderEventRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private DocumentStoreService documentStoreService;
    @Mock private RedisTemplate<String, Order> redisTemplate;
    @Mock private ValueOperations<String, Order> valueOperations;
    @Mock private OrderCacheManager orderCacheManager;

    // Real ObjectMapper — Mockito cannot reliably stub writeValueAsString()
    // because it's inherited across Jackson's class hierarchy
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // opsForValue() must return the mock before OrderService is built,
        // because createOrder() calls redisTemplate.opsForValue().get() on every request
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        orderService = new OrderService(
                orderRepository, orderEventRepository, outboxEventRepository,
                documentStoreService, redisTemplate, orderCacheManager, objectMapper);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Minimal valid CreateOrderRequest for a given customer. */
    private static CreateOrderRequest buildRequest(UUID customerId) {
        LineItemRequest item = LineItemRequest.builder()
                .productId(UUID.randomUUID())
                .sku("SKU-001")
                .productName("Blue Widget")
                .quantity(2)
                .unitPrice(new BigDecimal("25.00"))
                .build();

        return CreateOrderRequest.builder()
                .customerId(customerId)
                .lineItems(List.of(item))
                .shippingName("Jane Doe")
                .shippingLine1("1 Marina Blvd")
                .shippingCity("Singapore")
                .shippingState("SG")
                .shippingZip("018981")
                .shippingCountry("SG")
                .build();
    }

    /**
     * Build an Order in a given status.
     * customerId is set so cancelOrder / ownership checks work correctly.
     */
    private static Order buildOrder(OrderStatus status, UUID customerId) {
        return Order.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .status(status)
                .lineItems(new ArrayList<>())   // empty list avoids NPE in buildInvoiceContent()
                .totalAmount(BigDecimal.TEN)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  createOrder()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createOrder()")
    class CreateOrder {

        @Test
        @DisplayName("cache miss → saves order, writes OrderCreated outbox event, returns saved order")
        void createOrder_cacheMiss_savesOrderAndWritesOutboxEvent() {
            UUID customerId = UUID.randomUUID();

            // GIVEN — Redis has no entry for this idempotency key (first request)
            when(valueOperations.get(anyString())).thenReturn(null);

            // save() returns the same order it received (simulates JPA assigning an ID)
            when(orderRepository.save(any())).thenAnswer(inv -> {
                Order o = inv.getArgument(0);
                o.setId(UUID.randomUUID()); // JPA would set this; we do it manually
                return o;
            });
            when(orderEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // WHEN
            Order result = orderService.createOrder("idem-key-1", buildRequest(customerId));

            // THEN — order is created correctly
            assertThat(result).isNotNull();
            assertThat(result.getCustomerId()).isEqualTo(customerId);
            assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(result.getTotalAmount()).isEqualByComparingTo("50.00"); // 2 × 25.00

            // orderRepository.save() must be called exactly once
            verify(orderRepository, times(1)).save(any());

            // outbox event must be written with eventType "OrderCreated"
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("OrderCreated");
        }

        @Test
        @DisplayName("cache hit → returns cached order without touching the database")
        void createOrder_cacheHit_returnsCachedOrderWithoutSaving() {
            UUID customerId = UUID.randomUUID();
            Order cachedOrder = buildOrder(OrderStatus.PENDING, customerId);

            // GIVEN — Redis already has an entry (duplicate / retried request)
            when(valueOperations.get(anyString())).thenReturn(cachedOrder);

            // WHEN
            Order result = orderService.createOrder("idem-key-dup", buildRequest(customerId));

            // THEN — the cached order is returned as-is; no DB write happens
            assertThat(result).isEqualTo(cachedOrder);
            verify(orderRepository, never()).save(any());        // no DB insert
            verify(outboxEventRepository, never()).save(any()); // no outbox event
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  cancelOrder()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelOrder()")
    class CancelOrder {

        @Test
        @DisplayName("valid request → transitions order to CANCELLED, writes OrderStatusChanged event")
        void cancelOrder_validRequest_transitionsOrderToCancelled() {
            UUID customerId = UUID.randomUUID();
            Order order = buildOrder(OrderStatus.PENDING, customerId);

            when(orderCacheManager.getOrder(order.getId())).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orderEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // WHEN
            Order result = orderService.cancelOrder(order.getId(), customerId);

            // THEN — status is CANCELLED
            assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);

            // outbox event type must be "OrderStatusChanged"
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("OrderStatusChanged");
        }

        @Test
        @DisplayName("wrong customer → throws OrderAccessDeniedException, no save")
        void cancelOrder_wrongCustomer_throwsOrderAccessDeniedException() {
            UUID ownerId  = UUID.randomUUID();
            UUID callerId = UUID.randomUUID(); // different person trying to cancel

            Order order = buildOrder(OrderStatus.PENDING, ownerId);
            when(orderCacheManager.getOrder(order.getId())).thenReturn(Optional.of(order));

            // WHEN / THEN
            assertThatThrownBy(() -> orderService.cancelOrder(order.getId(), callerId))
                    .isInstanceOf(OrderAccessDeniedException.class);

            verify(orderRepository, never()).save(any()); // order must not be modified
        }

        @Test
        @DisplayName("order not found → throws OrderNotFoundException")
        void cancelOrder_orderNotFound_throwsOrderNotFoundException() {
            UUID unknownId = UUID.randomUUID();
            when(orderCacheManager.getOrder(unknownId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderService.cancelOrder(unknownId, UUID.randomUUID()))
                    .isInstanceOf(OrderNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  handlePaymentConfirmed()
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handlePaymentConfirmed()")
    class HandlePaymentConfirmed {

        @Test
        @DisplayName("PAYMENT_PROCESSING order → transitions to CONFIRMED, writes 2 outbox events")
        void handlePaymentConfirmed_paymentProcessingOrder_confirmsOrderAndWritesTwoOutboxEvents() {
            UUID customerId = UUID.randomUUID();
            Order order = buildOrder(OrderStatus.PAYMENT_PROCESSING, customerId);
            UUID paymentId  = UUID.randomUUID();
            UUID documentId = UUID.randomUUID();

            when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orderEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            // documentStoreService stores the invoice and returns its reference UUID
            when(documentStoreService.store(anyString(), anyString())).thenReturn(documentId);

            PaymentConfirmedEvent event = PaymentConfirmedEvent.builder()
                    .orderId(order.getId())
                    .paymentId(paymentId)
                    .amount(new BigDecimal("50.00"))
                    .build();

            // WHEN
            orderService.handlePaymentConfirmed(event);

            // THEN — order saved with status CONFIRMED
            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(orderCaptor.capture());
            assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.CONFIRMED);

            // THEN — exactly two outbox events: OrderStatusChanged + InvoiceGenerated
            ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository, times(2)).save(outboxCaptor.capture());
            assertThat(outboxCaptor.getAllValues())
                    .extracting(OutboxEvent::getEventType)
                    .containsExactlyInAnyOrder("OrderStatusChanged", "InvoiceGenerated");
        }
    }
}
