package com.oms.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = Map.ofEntries(
            Map.entry(OrderStatus.PENDING,            Set.of(OrderStatus.PAYMENT_PROCESSING, OrderStatus.CANCELLED)),
            Map.entry(OrderStatus.PAYMENT_PROCESSING, Set.of(OrderStatus.PAYMENT_FAILED, OrderStatus.CONFIRMED)),
            Map.entry(OrderStatus.PAYMENT_FAILED,     Set.of(OrderStatus.CANCELLED)),
            Map.entry(OrderStatus.CONFIRMED,          Set.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED)),
            Map.entry(OrderStatus.PROCESSING,         Set.of(OrderStatus.SHIPPED)),
            Map.entry(OrderStatus.SHIPPED,            Set.of(OrderStatus.DELIVERED)),
            Map.entry(OrderStatus.DELIVERED,          Set.of(OrderStatus.RETURN_REQUESTED)),
            Map.entry(OrderStatus.RETURN_REQUESTED,   Set.of(OrderStatus.RETURN_PROCESSING)),
            Map.entry(OrderStatus.RETURN_PROCESSING,  Set.of(OrderStatus.REFUNDED)),
            Map.entry(OrderStatus.CANCELLED,          Set.of()),
            Map.entry(OrderStatus.REFUNDED,           Set.of())
    );

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LineItem> lineItems = new ArrayList<>();

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "shipping_name")
    private String shippingName;

    @Column(name = "shipping_line1")
    private String shippingLine1;

    @Column(name = "shipping_city")
    private String shippingCity;

    @Column(name = "shipping_state")
    private String shippingState;

    @Column(name = "shipping_zip")
    private String shippingZip;

    @Column(name = "shipping_country")
    private String shippingCountry;

    private String notes;

    @Version
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public void transitionTo(OrderStatus newStatus) {
        Set<OrderStatus> allowed = VALID_TRANSITIONS.getOrDefault(this.status, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new com.oms.order.exception.IllegalOrderStateException(
                    "Cannot transition order from " + this.status + " to " + newStatus);
        }
        this.status = newStatus;
    }
}
