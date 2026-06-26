package com.oms.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.oms.inventory.domain.StockLevel;

@Repository
public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    List<StockLevel> findByProductId(UUID productId);

    Optional<StockLevel> findByProductIdAndWarehouseId(UUID productId, String warehouseId);

    // ALTERNATIVE — Pessimistic Lock approach (SELECT ... FOR UPDATE).
    // The DB locks the row the moment this is called. Every other pod trying
    // to read the same row is BLOCKED until the transaction that called this commits.
    // Use this when you need to read the data into Java first, do logic, then write back.
    // Trade-off: simpler logic, but causes queuing under high load.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockLevel s WHERE s.productId = :productId AND s.warehouseId = :warehouseId")
    Optional<StockLevel> findByProductIdAndWarehouseIdForUpdate(
            @Param("productId") UUID productId,
            @Param("warehouseId") String warehouseId);

    @Query("SELECT s FROM StockLevel s WHERE (s.quantityOnHand - s.quantityReserved) < :threshold")
    List<StockLevel> findAllBelowThreshold(@Param("threshold") int threshold);

    // FIX 3 — Atomic UPDATE for stock adjustments.
    // The WHERE clause makes the check and the write one unbreakable DB operation.
    // SQL: UPDATE stock_levels SET quantity_on_hand = quantity_on_hand + :adjustment
    //      WHERE product_id = :productId AND warehouse_id = :warehouseId
    //      AND (quantity_on_hand + :adjustment) >= 0
    // Returns 1 = success, 0 = adjustment would have made stock negative (rejected).
    @Modifying
    @Query("""
        UPDATE StockLevel s
        SET s.quantityOnHand = s.quantityOnHand + :adjustment
        WHERE s.productId = :productId
          AND s.warehouseId = :warehouseId
          AND (s.quantityOnHand + :adjustment) >= 0
        """)
    int adjustIfNonNegative(
            @Param("productId") UUID productId,
            @Param("warehouseId") String warehouseId,
            @Param("adjustment") int adjustment);

    // FIX 3 — Atomic UPDATE for stock reservation (order comes in).
    // Increments quantityReserved ONLY IF enough available stock exists.
    // SQL: UPDATE stock_levels SET quantity_reserved = quantity_reserved + :qty
    //      WHERE product_id = ? AND warehouse_id = ?
    //      AND (quantity_on_hand - quantity_reserved) >= :qty
    // Returns 1 = reserved, 0 = not enough stock available.
    @Modifying
    @Query("""
        UPDATE StockLevel s
        SET s.quantityReserved = s.quantityReserved + :qty
        WHERE s.productId = :productId
          AND s.warehouseId = :warehouseId
          AND (s.quantityOnHand - s.quantityReserved) >= :qty
        """)
    int reserveIfAvailable(
            @Param("productId") UUID productId,
            @Param("warehouseId") String warehouseId,
            @Param("qty") int qty);


    @Modifying
    @Query("""
        UPDATE StockLevel s
        SET s.quantityReserved = s.quantityReserved - :qty
        WHERE s.productId = :productId
          AND s.warehouseId = :warehouseId
          AND s.quantityReserved >= :qty
        """)
    int releaseIfAvailable(
            @Param("productId") UUID productId,
            @Param("warehouseId") String warehouseId,
            @Param("qty") int qty);
}
