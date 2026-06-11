# OrderFlow Event Catalog

Single source of truth for every message that crosses a service boundary. Schemas are defined in Avro under [`common/events/src/main/avro`](../common/events) and registered in the Confluent Schema Registry with **BACKWARD** compatibility mode (consumers can always read older data; fields may be added with defaults, never removed or retyped).

## Conventions

- **Naming:** `<Entity><PastTenseVerb>` for domain events (`PaymentApproved`), `<Verb><Entity>Command` for saga commands.
- **Topics:** `orderflow.<owner-service>.<purpose>.v<major>` — major version bumps only for breaking changes, run as parallel topics during migration.
- **Keying:** all saga-related messages are keyed by `orderId` (ordering guarantee per order).
- **Envelope:** every message carries a standard envelope (see below). Payloads contain IDs, never PII.

### Standard envelope (all messages)

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | Unique per event — the idempotency key for consumers |
| `eventType` | string | e.g. `PaymentApproved` |
| `aggregateId` | string | `orderId` for saga messages |
| `occurredAt` | timestamp-millis | Producer clock, UTC |
| `correlationId` | UUID | = original `orderId` request; spans the whole saga |
| `causationId` | UUID | `eventId` of the message that caused this one |
| `traceparent` | header | W3C trace context (Kafka header, not payload) |
| `schemaVersion` | int | Avro schema version registered for this subject |

## Topics

| Topic | Partitions | Retention | Cleanup | Producer |
|---|---|---|---|---|
| `orderflow.orders.events.v1` | 12 | 7 d | delete | order-service (outbox) |
| `orderflow.orders.saga-commands.v1` | 12 | 7 d | delete | order-service (outbox) |
| `orderflow.payments.events.v1` | 12 | 7 d | delete | payment-service (outbox) |
| `orderflow.inventory.events.v1` | 12 | 7 d | delete | inventory-service (outbox) |
| `orderflow.shipping.events.v1` | 12 | 7 d | delete | shipping-service (outbox) |
| `orderflow.orders.state.v1` | 12 | ∞ | compact | order-service (reconciliation source) |
| `*.retry` / `*.dlt` | mirrors source | 14 d | delete | consumer error handlers |

## Domain Events

### OrderCreated
- **Topic:** `orderflow.orders.events.v1` · **Producer:** order-service · **Consumers:** notification-service
- **Payload:**

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `customerId` | string | Reference only — no name/email in events |
| `items` | array<OrderItem> | `sku` (string), `quantity` (int), `unitPriceCents` (long) |
| `totalCents` | long | Money as integer cents — never floating point |
| `currency` | string | ISO 4217 |

### PaymentApproved
- **Topic:** `orderflow.payments.events.v1` · **Producer:** payment-service · **Consumers:** order-service (saga), notification-service

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `paymentId` | UUID | |
| `authorizedCents` | long | |
| `pspReference` | string | External PSP authorization id |

### PaymentDeclined

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `reasonCode` | enum | `INSUFFICIENT_FUNDS`, `FRAUD_SUSPECTED`, `CARD_EXPIRED`, `PSP_ERROR` |
| `retryable` | boolean | Saga retries `PSP_ERROR` up to 3×, never retries the others |

### PaymentRefunded

| Field | Type |
|---|---|
| `orderId` | UUID |
| `paymentId` | UUID |
| `refundedCents` | long |

### StockReserved

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `reservationId` | UUID | |
| `lines` | array | `sku`, `quantity`, `warehouseId` |
| `expiresAt` | timestamp | Reservation TTL — auto-released if saga dies silently |

### StockRejected

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `unavailable` | array | `sku`, `requested`, `available` |

### StockReleased

| Field | Type |
|---|---|
| `orderId` | UUID |
| `reservationId` | UUID |

### ShipmentScheduled

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `shipmentId` | UUID | |
| `carrier` | string | |
| `trackingCode` | string | |
| `estimatedDelivery` | date | |

### ShipmentFailed

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `reasonCode` | enum | `NO_CARRIER_CAPACITY`, `INVALID_ADDRESS`, `CARRIER_ERROR` |

### OrderCompleted / OrderCancelled
- **Topic:** `orderflow.orders.events.v1` · **Consumers:** notification-service, analytics (future)

| Field | Type | Description |
|---|---|---|
| `orderId` | UUID | |
| `finalState` | enum | `COMPLETED`, `CANCELLED` |
| `cancellationReason` | enum? | `PAYMENT_DECLINED`, `OUT_OF_STOCK`, `SHIPMENT_FAILED`, `TIMEOUT` |

## Saga Commands

Commands are *requests addressed to one service*; unlike events they imply an expected reply.

| Command | Topic | Handler | Success reply | Failure reply |
|---|---|---|---|---|
| `AuthorizePaymentCommand` | `orderflow.orders.saga-commands.v1` | payment-service | `PaymentApproved` | `PaymentDeclined` |
| `RefundPaymentCommand` | same | payment-service | `PaymentRefunded` | (retried; DLQ on exhaustion) |
| `ReserveStockCommand` | same | inventory-service | `StockReserved` | `StockRejected` |
| `ReleaseStockCommand` | same | inventory-service | `StockReleased` | (retried; DLQ on exhaustion) |
| `ScheduleShipmentCommand` | same | shipping-service | `ShipmentScheduled` | `ShipmentFailed` |

## Schema Evolution Policy

1. Compatibility mode `BACKWARD` enforced in CI (`gradle checkSchemaCompatibility` runs against the registry on every PR touching `common/events`).
2. Adding a field → must have a default. Removing/renaming/retyping → breaking → new `v(N+1)` topic with a documented dual-publish migration window.
3. Enums: consumers must treat unknown enum values as a defined `UNKNOWN` fallback (enforced by a shared deserializer wrapper) so producers can add values safely.
4. Every schema change requires updating this catalog in the same PR — enforced by a docs-checklist CI step.
