package com.oms.order.exception;

import java.util.UUID;

public class OrderAccessDeniedException extends RuntimeException {
    public OrderAccessDeniedException(UUID orderId) {
        super("Order " + orderId + " does not belong to this customer");
    }
}
