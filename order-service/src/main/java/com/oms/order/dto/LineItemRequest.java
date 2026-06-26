package com.oms.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LineItemRequest {

    @NotNull
    private UUID productId;

    @NotBlank
    private String sku;

    @NotBlank
    private String productName;

    @Min(1)
    private int quantity;

    @NotNull
    @Positive
    private BigDecimal unitPrice;
}
