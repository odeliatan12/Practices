package com.oms.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oms.inventory.domain.StockReservation;

@Repository
public interface StockReservationRepository extends JpaRepository<StockReservation, UUID>{
    
}
