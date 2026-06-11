# OrderFlow Public API Reference

All public traffic enters through the API Gateway at `https://api.orderflow.example` (locally `http://localhost:8080`). The full machine-readable spec is served at `/v3/api-docs` (OpenAPI 3.0.2) and browsable at `/swagger-ui.html`.

## Conventions

- **Auth:** `Authorization: Bearer <JWT>` on every request. Scopes: `orders:write`, `orders:read`.
- **Idempotency:** `POST /orders` requires an `Idempotency-Key` header (UUID). Retrying with the same key returns the original result (`200`) instead of creating a duplicate. Keys are retained for 24 h.
- **Errors:** RFC 9457 Problem Details (`application/problem+json`).
- **Money:** integer cents + ISO 4217 currency code. No floats, ever.
- **Async semantics:** order creation is accepted, not completed — the response is `202 Accepted` and the saga runs asynchronously. Clients poll status or subscribe to webhooks.
- **Rate limits:** 100 req/s per client (token bucket at the gateway). `429` with `Retry-After` when exceeded.

---

## POST /api/v1/orders

Create an order and start the processing saga.

**Headers**

| Header | Required | Notes |
|---|---|---|
| `Authorization` | yes | scope `orders:write` |
| `Idempotency-Key` | yes | UUID, unique per logical order attempt |

**Request body**

```json
{
  "customerId": "c-1001",
  "items": [
    { "sku": "SKU-RED-WIDGET", "quantity": 2 }
  ],
  "payment": { "method": "CREDIT_CARD", "token": "tok_visa_ok" },
  "shippingAddressId": "addr-77"
}
```

**Responses**

| Status | Meaning |
|---|---|
| `202 Accepted` | Order accepted; body contains `orderId`, `status: PENDING`, and a `Location` header to poll |
| `200 OK` | Idempotent replay — this key was already processed; original response returned |
| `400` | Validation error (empty items, quantity ≤ 0, unknown payment method) |
| `401 / 403` | Missing/insufficient auth |
| `409` | Idempotency-Key reused with a *different* request body |
| `429` | Rate limited |

**202 response body**

```json
{
  "orderId": "8f7e0a3c-2a4b-4f1d-9a36-0d1d6f0e9b21",
  "status": "PENDING",
  "links": { "self": "/api/v1/orders/8f7e0a3c-..." }
}
```

---

## GET /api/v1/orders/{orderId}

Current order state, including saga progress and state history.

**Response `200`**

```json
{
  "orderId": "8f7e0a3c-2a4b-4f1d-9a36-0d1d6f0e9b21",
  "status": "STOCK_RESERVED",
  "totalCents": 5980,
  "currency": "BRL",
  "items": [{ "sku": "SKU-RED-WIDGET", "quantity": 2, "unitPriceCents": 2990 }],
  "history": [
    { "status": "PENDING",          "at": "2026-06-11T14:03:21.120Z" },
    { "status": "PAYMENT_APPROVED", "at": "2026-06-11T14:03:21.310Z" },
    { "status": "STOCK_RESERVED",   "at": "2026-06-11T14:03:21.402Z" }
  ],
  "cancellationReason": null
}
```

`404` if unknown, `403` if the JWT subject does not own the order.

---

## GET /api/v1/orders?customerId=&status=&page=&size=

Paginated order listing (scope `orders:read`). Cursor-based pagination via `nextCursor`; max `size` 100.

---

## DELETE /api/v1/orders/{orderId}

Customer-initiated cancellation. Allowed only while status is `PENDING` or `PAYMENT_APPROVED`; later states return `409 CONFLICT` with `problem.type = order-not-cancellable`. Cancellation runs through the same saga compensation machinery (refund + release).

| Status | Meaning |
|---|---|
| `202` | Cancellation saga started |
| `409` | Order is past the point of no return (`STOCK_RESERVED`+) |

---

## POST /api/v1/webhooks/subscriptions

Register a webhook to receive order lifecycle notifications instead of polling.

```json
{ "url": "https://client.example/hooks/orders", "events": ["OrderCompleted", "OrderCancelled"] }
```

Deliveries are signed (`X-OrderFlow-Signature`, HMAC-SHA256) and retried with exponential backoff for 24 h. Consumers must be idempotent — deliveries are at-least-once (of course).

---

## Error format (RFC 9457)

```json
{
  "type": "https://api.orderflow.example/problems/out-of-stock",
  "title": "Item out of stock",
  "status": 409,
  "detail": "SKU-RED-WIDGET: requested 9999, available 41",
  "instance": "/api/v1/orders/8f7e0a3c-...",
  "traceId": "7be2c0e44a1b4f0a"
}
```

`traceId` links the error directly to the distributed trace in Grafana Tempo — include it in support requests.
