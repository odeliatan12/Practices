package com.oms.order.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOrderRequest {

    @NotNull
    private UUID customerId;

    @NotEmpty(message = "Order must have at least one item")
    @Valid
    private List<LineItemRequest> lineItems;

    @NotBlank
    private String shippingName;

    @NotBlank
    private String shippingLine1;

    @NotBlank
    private String shippingCity;

    @NotBlank
    private String shippingState;

    @NotBlank
    private String shippingZip;

    @NotBlank
    private String shippingCountry;

    private String notes;
}
