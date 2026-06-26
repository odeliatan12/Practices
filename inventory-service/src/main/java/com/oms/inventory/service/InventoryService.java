package com.oms.inventory.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oms.inventory.domain.Product;

import com.oms.inventory.domain.StockLevel;
import com.oms.inventory.dto.ReservationResponse;
import com.oms.inventory.dto.StockLevelResponse;
import com.oms.inventory.repository.InventoryRepository;
import com.oms.inventory.repository.StockLevelRepository;
import com.oms.inventory.repository.StockReservationRepository;

@Service
public class InventoryService {
    
    private final InventoryRepository inventoryRepository;
    private final StockLevelRepository stockLevelRepository;
    private final StockReservationRepository stockReservationRepository;

    public InventoryService(InventoryRepository inventoryRepository, StockLevelRepository stockLevelRepository, StockReservationRepository stockReservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.stockLevelRepository = stockLevelRepository;
        this.stockReservationRepository = stockReservationRepository;
    }

    @Transactional(readOnly = true)
    public StockLevelResponse getStockLevel(UUID productId, String warehouseId) {

        Product product = inventoryRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        StockLevel stockLevel = stockLevelRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() -> new RuntimeException("Stock not found for product: " + productId + " warehouse: " + warehouseId));

        return StockLevelResponse.builder()
                .productId(product.getId())
                .sku(product.getSku())
                .productName(product.getName())
                .warehouseId(stockLevel.getWarehouseId())
                .quantityOnHand(stockLevel.getQuantityOnHand())
                .quantityReserved(stockLevel.getQuantityReserved())
                .quantityAvailable(stockLevel.getQuantityAvailable())
                .lowStockThreshold(product.getLowStockThreshold())
                .isLowStock(stockLevel.getQuantityAvailable() < product.getLowStockThreshold())
                .build();
    }

    @Transactional
    public StockLevelResponse adjustStockLevel(UUID productId, String warehouseId, int adjustment, String reason) {
        // BEFORE (buggy):
        //   read qty → check qty+adjustment >= 0 → write new qty
        //   Two concurrent calls both read qty=5, both pass the check,
        //   both write — stock silently goes negative.
        //
        // AFTER (fixed — Atomic UPDATE, Fix 3):
        //   The WHERE clause makes the check and the decrement one unbreakable DB operation.
        //   The database engine evaluates (qty + adjustment >= 0) and writes in one step.
        //   If two threads race, only the first succeeds; the second gets rowsUpdated=0.

        int rowsUpdated = stockLevelRepository.adjustIfNonNegative(productId, warehouseId, adjustment);

        if (rowsUpdated == 0) {
            // Either the product/warehouse doesn't exist, or the adjustment would go negative.
            StockLevel current = stockLevelRepository
                    .findByProductIdAndWarehouseId(productId, warehouseId)
                    .orElseThrow(() -> new RuntimeException(
                            "Stock not found for product: " + productId + " warehouse: " + warehouseId));
            throw new RuntimeException(
                    "Adjustment would result in negative stock. Current: "
                    + current.getQuantityOnHand() + ", adjustment: " + adjustment);
        }

        return getStockLevel(productId, warehouseId);
    }

    @Transactional
    public ReservationResponse reserveStock(UUID productId, String warehouseId, int qty) {
        
        int rowsUpdated = stockLevelRepository.reserveIfAvailable(productId, warehouseId, qty);
        if(rowsUpdated == 1){
            StockLevel stockLevel = stockLevelRepository.findByProductIdAndWarehouseId(productId, warehouseId)
            .orElseThrow(() -> new RuntimeException("Stock not found"));

            return ReservationResponse.builder()
                .productId(productId)
                .warehouseId(warehouseId)
                .reserved(true)
                .remainingStock(stockLevel.getQuantityAvailable())
                .message("Stock reserved successfully")
                .build();
        } 
        // Read the real stock level so we can tell the caller how much IS available.
        // The user may have asked for 10 but only 3 exist — we return 3, not 0.
        StockLevel stockLevel = stockLevelRepository
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() -> new RuntimeException("Stock not found"));

        return ReservationResponse.builder()
                .productId(productId)
                .warehouseId(warehouseId)
                .reserved(false)
                .remainingStock(stockLevel.getQuantityAvailable())
                .message("Insufficient stock. Only " + stockLevel.getQuantityAvailable() + " units available.")
                .build();
        
    }

    @Transactional
    public ReservationResponse releaseStock(UUID productId, String warehouseId, int qty){
        int rowsUpdated = stockLevelRepository.releaseIfAvailable(productId, warehouseId, qty);

        StockLevel stockLevel = stockLevelRepository
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() -> new RuntimeException("Stock not found"));
        if(rowsUpdated == 1){
                return ReservationResponse.builder()
                    .productId(productId)
                    .warehouseId(warehouseId)
                    .remainingStock(stockLevel.getQuantityAvailable())
                    .reserved(true)
                    .message("Stock released successfully")
                    .build();
        } else {
                return ReservationResponse.builder()
                        .productId(productId)
                        .warehouseId(warehouseId)
                        .remainingStock(stockLevel.getQuantityAvailable())
                        .reserved(false)
                        .message("Cannot release. Only " + stockLevel.getQuantityReserved() + " units currently reserved.")
                        .build();
        }
    }

    @Transactional
    public List<ReservationResponse> transferStock(UUID productId, String fromWarehouseId, String toWarehouseId, int qty) {

        // Step 1: Verify both warehouses exist BEFORE attempting any UPDATE.
        // If either is missing we throw 404 immediately — no DB changes have happened yet.
        // Checking destination first avoids a situation where we deduct from source
        // and then discover the destination does not exist.
        StockLevel sourceStock = stockLevelRepository
                .findByProductIdAndWarehouseId(productId, fromWarehouseId)
                .orElseThrow(() -> new RuntimeException("Source warehouse not found: " + fromWarehouseId));

        // Check destination exists before deducting from source.
        // If we deducted first and then found destination missing, stock would disappear.
        stockLevelRepository
                .findByProductIdAndWarehouseId(productId, toWarehouseId)
                .orElseThrow(() -> new RuntimeException("Destination warehouse not found: " + toWarehouseId));

        // Step 2: Deduct from source warehouse (atomic UPDATE with WHERE guard).
        // Returns 0 if insufficient stock — the guard (quantityOnHand + adjustment >= 0) fails.
        int rowsDeducted = stockLevelRepository.adjustIfNonNegative(productId, fromWarehouseId, -qty);

        if (rowsDeducted == 0) {
            // Not enough stock in source — return failure for source warehouse only.
            // Destination is unaffected — no changes have been made to the DB.
            // List.of() creates an immutable list — the correct way to return multiple items.
            return List.of(
                ReservationResponse.builder()
                    .productId(productId)
                    .warehouseId(fromWarehouseId)
                    .reserved(false)
                    .remainingStock(sourceStock.getQuantityAvailable())
                    .message("Insufficient stock in source warehouse. Only "
                            + sourceStock.getQuantityAvailable() + " units available.")
                    .build()
            );
        }

        // Step 3: Add to destination warehouse.
        // Adding stock always passes the WHERE guard (quantityOnHand + qty >= 0 is always true
        // when qty is positive). We already confirmed the row exists in Step 1.
        // @Transactional guarantees: if this somehow fails, Step 2 rolls back automatically.
        stockLevelRepository.adjustIfNonNegative(productId, toWarehouseId, qty);

        // Step 4: Re-read BOTH stock levels after both UPDATEs to return accurate values.
        // Reading before the UPDATEs would return stale numbers.
        StockLevel updatedSource = stockLevelRepository
                .findByProductIdAndWarehouseId(productId, fromWarehouseId)
                .orElseThrow(() -> new RuntimeException("Source warehouse not found"));

        StockLevel updatedDest = stockLevelRepository
                .findByProductIdAndWarehouseId(productId, toWarehouseId)
                .orElseThrow(() -> new RuntimeException("Destination warehouse not found"));

        // Return one response per warehouse so the caller sees the state of both.
        return List.of(
            ReservationResponse.builder()
                .productId(productId)
                .warehouseId(fromWarehouseId)
                .reserved(true)
                .remainingStock(updatedSource.getQuantityAvailable())
                .message("Transferred " + qty + " units out of " + fromWarehouseId)
                .build(),
            ReservationResponse.builder()
                .productId(productId)
                .warehouseId(toWarehouseId)
                .reserved(true)
                .remainingStock(updatedDest.getQuantityAvailable())
                .message("Received " + qty + " units into " + toWarehouseId)
                .build()
        );
    }

    @Transactional(readOnly = true)
    public List<StockLevelResponse> getLowStockItems() {
        return stockLevelRepository.findAll().stream()
                .filter(sl -> {
                    int threshold = inventoryRepository.findById(sl.getProductId())
                            .map(p -> p.getLowStockThreshold())
                            .orElse(10);
                    return sl.getQuantityAvailable() < threshold;
                })
                .map(sl -> {
                    Product product = inventoryRepository.findById(sl.getProductId()).orElse(null);
                    return StockLevelResponse.builder()
                            .productId(sl.getProductId())
                            .sku(product != null ? product.getSku() : null)
                            .productName(product != null ? product.getName() : null)
                            .warehouseId(sl.getWarehouseId())
                            .quantityOnHand(sl.getQuantityOnHand())
                            .quantityReserved(sl.getQuantityReserved())
                            .quantityAvailable(sl.getQuantityAvailable())
                            .lowStockThreshold(product != null ? product.getLowStockThreshold() : 10)
                            .isLowStock(true)
                            .build();
                })
                .toList();
    }


}
