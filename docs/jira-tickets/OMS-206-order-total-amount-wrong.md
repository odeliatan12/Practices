# OMS-206 — Order Total Amount Incorrect — Rounding Error on Multi-Item Orders

| Field       | Value                           |
|-------------|---------------------------------|
| Priority    | P2 — High                       |
| Reporter    | Finance Team                    |
| Assignee    | Unassigned                      |
| Created     | 2026-06-26 17:00 SGT            |
| Environment | Production                      |
| Service     | order-service                   |

---

## Description

The Finance team ran a daily reconciliation and found that order totals stored
in the database do not match what customers were quoted on the checkout page.

For orders with multiple line items, the stored `total_amount` is sometimes
off by $0.01 compared to the sum of `(unit_price × quantity)` for each item.

This has caused incorrect invoice amounts and customer complaints about
being overcharged.

---

## Example

Order `658514c8-6ed9-4686-b79f-50b8d6544c8b`:

| Item              | Qty | Unit Price | Expected Subtotal |
|-------------------|-----|------------|-------------------|
| Red Cotton T-Shirt | 2  | $29.99     | $59.98            |
| Black Chino Pants  | 1  | $59.99     | $59.99            |
| **Total**         |     |            | **$119.97**       |

Stored `total_amount` in database: **$119.96** ❌

---

## Steps to Reproduce

```bash
TOKEN="<your-jwt-token>"

# Create an order with items that have fractional prices
curl -s -X POST http://localhost:8082/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "customerId": "a1b2c3d4-0000-0000-0000-000000000001",
    "lineItems": [
      {
        "productId": "11100000-0000-0000-0000-000000000001",
        "sku": "SHIRT-RED-L",
        "productName": "Red Cotton T-Shirt",
        "quantity": 3,
        "unitPrice": 19.99
      },
      {
        "productId": "22200000-0000-0000-0000-000000000002",
        "sku": "PANTS-BLK-32",
        "productName": "Black Chino Pants",
        "quantity": 2,
        "unitPrice": 49.99
      }
    ],
    "shippingName": "John", "shippingLine1": "123 St",
    "shippingCity": "Singapore", "shippingState": "SG",
    "shippingZip": "018989", "shippingCountry": "SG"
  }' | jq '{id, totalAmount, lineItems: [.lineItems[] | {productName, quantity, unitPrice, subtotal}]}'

# Expected total: (3 × 19.99) + (2 × 49.99) = 59.97 + 99.98 = 159.95
# Verify the returned totalAmount matches
```

---

## Suspected Root Cause

The `totalAmount` is calculated in `OrderService.createOrder()`:

```java
BigDecimal totalAmount = lineItems.stream()
    .map(LineItem::getSubtotal)
    .reduce(BigDecimal.ZERO, BigDecimal::add);
```

And `getSubtotal()` in `LineItem`:

```java
public BigDecimal getSubtotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
}
```

The issue is likely that `BigDecimal.valueOf(quantity)` followed by `multiply`
uses the default `MathContext`, which may cause scale inconsistencies. Or the
`unit_price` column scale in the database (precision 10, scale 2) may be
truncating values during read.

Check the `@Column(precision, scale)` annotation on `unitPrice` and whether
`setScale(2, RoundingMode.HALF_UP)` is applied before summing.

---

## Debugging Steps to Try

```bash
# 1. Check the database directly for a known order
docker exec -it oms-postgres psql -U oms -d orders_db -c "
  SELECT
    o.id,
    o.total_amount,
    SUM(li.unit_price * li.quantity) AS calculated_total,
    o.total_amount - SUM(li.unit_price * li.quantity) AS discrepancy
  FROM orders o
  JOIN line_items li ON li.order_id = o.id
  GROUP BY o.id, o.total_amount
  HAVING o.total_amount != SUM(li.unit_price * li.quantity)
  LIMIT 10;
"

# 2. Check the column definition
docker exec -it oms-postgres psql -U oms -d orders_db -c \
  "\d+ line_items"

# 3. Find where total is calculated
grep -n "getSubtotal\|totalAmount\|reduce\|BigDecimal" \
  order-service/src/main/java/com/oms/order/service/OrderService.java

grep -n "getSubtotal\|multiply\|RoundingMode" \
  order-service/src/main/java/com/oms/order/domain/LineItem.java
```

---

## Impact

- Finance reconciliation fails daily
- Customers may be over or undercharged by $0.01 per order
- Potential regulatory issue for financial accuracy

---

## Acceptance Criteria

- [ ] `total_amount` in database exactly equals `SUM(unit_price × quantity)` for all line items
- [ ] No discrepancy found by the reconciliation query above
- [ ] All edge cases tested: fractional prices, large quantities, multiple items
