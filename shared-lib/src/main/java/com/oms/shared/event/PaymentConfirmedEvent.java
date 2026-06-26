package com.oms.shared.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentConfirmedEvent {
    private UUID orderId;
    private UUID paymentId;
    private BigDecimal amount;
}
