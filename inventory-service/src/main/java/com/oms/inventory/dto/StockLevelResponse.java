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
public class StockLevelResponse {

    private UUID productId;
    private String sku;
    private String productName;
    private String warehouseId;
    private int quantityOnHand;
    private int quantityReserved;
    private int quantityAvailable;
    private int lowStockThreshold;
    private boolean isLowStock;
}
