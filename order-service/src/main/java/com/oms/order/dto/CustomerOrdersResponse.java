package com.oms.order.dto;

import java.util.List;

import com.oms.order.domain.Order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrdersResponse {
    private List<Order> currentOrders;
    private List<Order> pastOrders;
}
