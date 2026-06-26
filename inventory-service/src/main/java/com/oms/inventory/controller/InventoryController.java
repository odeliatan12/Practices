package com.oms.inventory.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oms.inventory.dto.ReservationResponse;
import com.oms.inventory.dto.StockAdjustmentRequest;
import com.oms.inventory.dto.StockLevelResponse;
import com.oms.inventory.service.InventoryService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @ApiResponse(responseCode = "200", description = "Get stock level for a product in a warehouse")
    @GetMapping("/products/{productId}/stock")
    public ResponseEntity<StockLevelResponse> getStockLevel(
            @PathVariable UUID productId,
            @RequestParam String warehouseId) {
        return ResponseEntity.ok(inventoryService.getStockLevel(productId, warehouseId));
    }

    @ApiResponse(responseCode = "200", description = "Adjust stock level manually")
    @PatchMapping("/products/{productId}/stock")
    public ResponseEntity<StockLevelResponse> adjustStockLevel(
            @PathVariable UUID productId,
            @Valid @RequestBody StockAdjustmentRequest request) {
        return ResponseEntity.ok(inventoryService.adjustStockLevel(
                productId,
                request.getWarehouseId(),
                request.getAdjustment(),
                request.getReason()));
    }

    @ApiResponse(responseCode = "200", description = "List all products with low stock")
    @GetMapping("/products/low-stock")
    public ResponseEntity<List<StockLevelResponse>> getLowStockItems1() {
        return ResponseEntity.ok(inventoryService.getLowStockItems());
    }

    @ApiResponse(responseCode = "200", description = "Allow Users to reserve the order")
    @PostMapping("/products/{productId}/reserve")
    public ResponseEntity<ReservationResponse> reserveStock(@PathVariable UUID productId, @RequestParam String warehouseId, @RequestParam int qty) {
        ReservationResponse response = inventoryService.reserveStock(productId, warehouseId, qty);
        if(response.isReserved()){
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    @ApiResponse(responseCode = "200", description = "Allow Users to release the order")
    @PostMapping("/products/{productId}/release")
    public ResponseEntity<ReservationResponse> releaseStock(@PathVariable UUID productId, @RequestParam String warehouseId, @RequestParam int qty){
        ReservationResponse response = inventoryService.releaseStock(productId, warehouseId, qty);
        if(response.isReserved()){
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    @ApiResponse(responseCode = "200", description = "Transfer stock from one warehouse to another")
    @PostMapping("/products/{productId}/transfer")
    public ResponseEntity<List<ReservationResponse>> transferStock(
            @PathVariable UUID productId,
            @RequestParam String fromWarehouseId,
            @RequestParam String toWarehouseId,
            @RequestParam int qty) {

        List<ReservationResponse> responses = inventoryService.transferStock(productId, fromWarehouseId, toWarehouseId, qty);

        // If any response has reserved=false, the transfer failed — return 409
        boolean anyFailed = responses.stream().anyMatch(r -> !r.isReserved());
        if (anyFailed) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(responses);
        }
        return ResponseEntity.ok(responses);
    }
}
