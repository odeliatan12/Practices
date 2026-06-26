package com.oms.order.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.oms.order.domain.Order;
import com.oms.order.domain.OrderStatus;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Order> findByStatus(OrderStatus status);

    List<Order> findByCustomerIdAndStatus(UUID customerId, OrderStatus status);

    List<Order> findByCustomerIdAndStatusInOrderByCreatedAtAsc(UUID customerId, List<OrderStatus> statuses);

    List<Order> findByCustomerIdAndStatusNotInOrderByCreatedAtAsc(UUID customerId, List<OrderStatus> statuses);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findAll(Pageable pageable);
}
