package com.oms.order.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oms.order.domain.DocumentStore;

@Repository
public interface DocumentStoreRepository extends JpaRepository<DocumentStore, UUID> {
}
