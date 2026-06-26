package com.oms.inventory.domain;

public enum StockReservationStatus {
    PENDING,      // stock is held, waiting for payment confirmation
    CONFIRMED,    // payment captured, stock permanently deducted
    RELEASED      // order cancelled or payment failed, stock returned
}
