package com.oms.inventory.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
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
public class StockReservationStatus {

    @NotNull
    private UUID orderId;

    @Valid
    @NotEmpty(message = "Reservation must have at least one item")
    private List<ReservationItem> items;
    
}
