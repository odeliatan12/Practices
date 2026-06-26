package com.oms.inventory.dto;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationResponse {

    private UUID productId;
    private String warehouseId;
    private boolean reserved;
    private String message;
    private int remainingStock;
}
