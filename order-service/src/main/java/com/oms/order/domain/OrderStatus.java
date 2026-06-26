package com.oms.order.domain;

public enum OrderStatus {
    PENDING,
    PAYMENT_PROCESSING,
    PAYMENT_FAILED,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURN_PROCESSING,
    REFUNDED
}
