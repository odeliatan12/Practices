package com.oms.order.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oms.order.domain.Order;
import com.oms.order.domain.OrderEvent;
import com.oms.order.domain.OrderStatus;
import com.oms.order.dto.CreateOrderRequest;
import com.oms.order.dto.CustomerOrdersResponse;
import com.oms.order.service.OrderService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @ApiResponse(responseCode = "201", description = "Create a new order")
    @PostMapping
    public ResponseEntity<Order> createOrder(
            @Valid @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @ApiResponse(responseCode = "200", description = "Get order by ID")
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @ApiResponse(responseCode = "200", description = "Get past and current orders for a customer")
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<CustomerOrdersResponse> getOrders(@PathVariable UUID customerId) {
        return ResponseEntity.ok(orderService.getOrders(customerId));
    }

    @ApiResponse(responseCode = "200", description = "Cancel an order")
    @PatchMapping("/{orderId}/cancel")
    public ResponseEntity<Order> cancelOrder(
            @PathVariable UUID orderId,
            @RequestParam UUID customerId) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId, customerId));
    }

    @ApiResponse(responseCode = "200", description = "Get order status history")
    @GetMapping("/{orderId}/history")
    public ResponseEntity<List<OrderEvent>> getOrderHistory(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrderEvents(orderId));
    }

    @ApiResponse(responseCode = "200", description = "Get all orders (staff view)")
    @GetMapping("/staff")
    public ResponseEntity<Page<Order>> getStaffOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(orderService.getStaffOrders(status, page, size));
    }
}
