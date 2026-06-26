package com.oms.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oms.inventory.domain.Product;

@Repository
public interface InventoryRepository extends JpaRepository<Product, UUID> {
    
    
}
