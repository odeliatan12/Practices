package com.oms.shared.event;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceGeneratedEvent {
    private UUID orderId;
    private UUID customerId;
    private UUID documentId;  // reference to document_store — not the invoice itself
}
