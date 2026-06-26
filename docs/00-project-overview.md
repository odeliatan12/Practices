# 00 — Project Overview

## What We're Building

An **Order Management System (OMS)** — a production-grade microservice application that handles the full lifecycle of customer orders: placement, payment, fulfillment, shipping, and returns.

This is not a toy CRUD app. The goal is to practice building a system that could realistically handle tens of thousands of orders per day with reliability, observability, and scalability built in from the start.

---

## Core Problem

E-commerce and marketplace platforms need a centralized system that:

- Accepts orders from multiple channels (web, mobile, third-party APIs)
- Coordinates inventory checks, payment processing, and warehouse fulfillment
- Keeps customers informed at every step via notifications
- Handles failures gracefully (payment declined, item out of stock, shipping delays)
- Gives ops teams full visibility into the order pipeline

---

## High-Level Features

### Customer-Facing
- Place orders with multiple line items
- Real-time order status tracking
- Order history and receipts
- Cancel or modify orders before fulfillment
- Request returns and refunds

### Operations / Admin
- Dashboard with live order feed
- Inventory management and low-stock alerts
- Manual order overrides and status updates
- Bulk order export (CSV / PDF)
- SLA breach alerts

### Integrations
- Payment gateway (Stripe)
- Shipping carrier (FedEx / UPS via adapter pattern)
- Email & SMS notifications (SendGrid / Twilio)
- Warehouse Management System (WMS) webhook

---

## Domain Concepts

| Term | Definition |
|---|---|
| **Order** | A customer's intent to purchase one or more products |
| **Line Item** | A single product+quantity+price within an order |
| **SKU** | Stock-keeping unit — a unique product variant identifier |
| **Fulfillment** | The warehouse process of picking, packing, and shipping |
| **Shipment** | A physical package dispatched for one or more line items |
| **Return** | A customer-initiated reversal of a delivered order |
| **Saga** | A distributed transaction spanning multiple services |

---

## Order Lifecycle (State Machine)

```
PENDING → PAYMENT_PROCESSING → PAYMENT_FAILED
                             → CONFIRMED → PROCESSING → SHIPPED → DELIVERED
                                                                 → RETURN_REQUESTED → REFUNDED
                             → CANCELLED
```

Each state transition triggers downstream events consumed by other services.

---

## Technology Choices (to implement)

| Layer | Technology |
|---|---|
| API Gateway | Spring Cloud Gateway |
| Backend Services | Spring Boot 3 (Java 21) |
| Async Messaging | Apache Kafka |
| Cache | Redis |
| Primary DB | PostgreSQL |
| Search | Elasticsearch |
| Real-time | WebSocket (STOMP) |
| Auth | JWT + OAuth2 |
| Container | Docker + Kubernetes |
| CI/CD | GitHub Actions |

---

## What You'll Practice

- Microservice decomposition and bounded contexts
- Event-driven architecture with Kafka sagas
- Distributed data management (each service owns its DB)
- Idempotency and exactly-once processing
- Observability: structured logging, metrics, distributed tracing
- Security: auth, authorization, input validation
- Production readiness: health checks, circuit breakers, graceful shutdown
