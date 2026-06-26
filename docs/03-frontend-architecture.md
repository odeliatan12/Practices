# 03 — Frontend Architecture

## Stack

- **Framework**: React 18 with TypeScript
- **Build tool**: Vite
- **State management**: Zustand (global) + React Query (server state)
- **Routing**: React Router v6
- **UI components**: shadcn/ui + Tailwind CSS
- **Real-time**: WebSocket via STOMP.js
- **HTTP client**: Axios with interceptors
- **Forms**: React Hook Form + Zod validation

---

## Project Structure

```
frontend/
├── public/
├── src/
│   ├── pages/
│   │   ├── LoginPage.tsx
│   │   ├── DashboardPage.tsx
│   │   ├── OrdersPage.tsx           ← paginated order list
│   │   ├── OrderDetailPage.tsx      ← single order view + timeline
│   │   ├── PlaceOrderPage.tsx       ← order creation form
│   │   ├── InventoryPage.tsx        ← admin: stock management
│   │   └── AdminDashboardPage.tsx   ← ops overview
│   ├── components/
│   │   ├── orders/
│   │   │   ├── OrderCard.tsx
│   │   │   ├── OrderStatusBadge.tsx
│   │   │   ├── OrderTimeline.tsx    ← status history visualization
│   │   │   └── LineItemTable.tsx
│   │   ├── inventory/
│   │   │   ├── StockTable.tsx
│   │   │   └── LowStockAlert.tsx
│   │   ├── layout/
│   │   │   ├── Navbar.tsx
│   │   │   ├── Sidebar.tsx
│   │   │   └── PageShell.tsx
│   │   └── shared/
│   │       ├── DataTable.tsx        ← reusable paginated table
│   │       ├── StatusBadge.tsx
│   │       └── ErrorBoundary.tsx
│   ├── hooks/
│   │   ├── useOrders.ts             ← React Query hooks
│   │   ├── useOrderDetail.ts
│   │   ├── useWebSocket.ts          ← live order updates
│   │   └── useAuth.ts
│   ├── api/
│   │   ├── client.ts                ← Axios instance + interceptors
│   │   ├── orders.ts                ← API call functions
│   │   ├── inventory.ts
│   │   └── auth.ts
│   ├── store/
│   │   ├── authStore.ts             ← Zustand: user session
│   │   └── notificationStore.ts    ← Zustand: toast queue
│   ├── types/
│   │   ├── order.ts
│   │   ├── inventory.ts
│   │   └── api.ts                   ← response envelope types
│   └── utils/
│       ├── formatCurrency.ts
│       ├── formatDate.ts
│       └── orderStatusColor.ts
```

---

## Key Pages

### Dashboard (Admin)
- KPI cards: total orders today, revenue, orders by status
- Live order feed (WebSocket) — new orders appear without refresh
- Chart: order volume over time (last 7 days)
- SLA breach list: orders stuck in `PROCESSING` > 24h

### Orders List Page
- Searchable, filterable, sortable table
- Filter by status, date range, customer
- Cursor-based pagination
- Click row → Order Detail

### Order Detail Page
- Full order info: items, customer, shipping address, totals
- Status timeline (visual stepper)
- Action buttons: Cancel Order, Request Return (context-aware)
- Live status updates via WebSocket — badge updates without reload

### Place Order Page
- Multi-step form: select items → shipping → review → confirm
- Real-time stock availability check per SKU
- Form validation before submission
- Redirect to Order Detail on success

### Inventory Page (Admin)
- Table of SKUs with stock levels
- Inline edit to adjust stock
- Low-stock alerts highlighted in red
- Bulk import via CSV upload

---

## State Management Strategy

| State Type | Tool |
|---|---|
| Server data (orders, inventory) | React Query — caches, refetches, handles loading/error |
| User session (JWT, roles) | Zustand — persisted to localStorage |
| UI state (modals, toasts) | Zustand or local `useState` |
| Form state | React Hook Form |
| Live updates | WebSocket → update React Query cache directly |

---

## Authentication Flow

1. User submits email + password on `LoginPage`
2. POST `/auth/login` → receives `accessToken` (JWT) + `refreshToken`
3. Tokens stored in memory (access) and httpOnly cookie (refresh)
4. Axios interceptor attaches `Authorization: Bearer <token>` to every request
5. On 401 response, interceptor calls `/auth/refresh`, retries original request
6. On logout, tokens cleared, user redirected to login

---

## Real-Time Updates

Uses a single shared WebSocket connection per user session.

- Connect on login, disconnect on logout
- Subscribe to `/topic/orders/{customerId}` — receives status updates for own orders
- Admin subscribes to `/topic/orders/all` — receives all incoming orders
- On receiving a message: invalidate the relevant React Query key → triggers refetch

---

## Error Handling

- Axios response interceptor catches non-2xx and translates to typed errors
- React Query `onError` callbacks surface errors to UI
- `ErrorBoundary` wraps each page — catches render errors
- Toast notifications for user-facing errors (network failure, validation rejection)

---

## Routing Structure

```
/login
/dashboard
/orders
/orders/:orderId
/orders/new
/admin/inventory
/admin/dashboard
```

Protected routes check the auth store for a valid session; redirect to `/login` if missing.
