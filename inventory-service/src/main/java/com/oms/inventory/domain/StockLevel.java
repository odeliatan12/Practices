package com.oms.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stock_levels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false)
    private String warehouseId;

    @Column(name = "quantity_on_hand", nullable = false)
    @Builder.Default
    private int quantityOnHand = 0;

    @Column(name = "quantity_reserved", nullable = false)
    @Builder.Default
    private int quantityReserved = 0;

    // Safety net: JPA throws OptimisticLockingFailureException if two transactions
    // try to write the same row concurrently without going through the atomic UPDATE query.
    @Version
    private int version;

    @Transient
    public int getQuantityAvailable() {
        return quantityOnHand - quantityReserved;
    }

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
