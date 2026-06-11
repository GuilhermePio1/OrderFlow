# ADR-0003: Transactional Outbox with Debezium CDC

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** José Guilherme (author)

## Context

Every saga participant must do two things atomically: **commit a local state change** (e.g., mark payment approved in PostgreSQL) and **publish an event** (e.g., `PaymentApproved` to Kafka). These are two different systems, so naive code has a dual-write problem:

```java
paymentRepository.save(payment);        // commits to Postgres
kafkaTemplate.send("payments", event);  // what if this fails? Or the JVM dies between the lines?
```

- DB commit succeeds, publish fails → the saga hangs forever (order stuck in `PENDING`).
- Publish succeeds, DB commit fails (publish-first variant) → the saga advances on state that doesn't exist.

Wrapping both in a Kafka transaction doesn't help — Kafka transactions don't span PostgreSQL.

## Decision

Use the **Transactional Outbox** pattern with **Debezium** as the relay:

1. The service writes its state change **and** an event row into an `outbox` table **in the same local ACID transaction**. One commit, atomic by construction.
2. Debezium tails the PostgreSQL WAL (logical replication) and publishes each outbox row to Kafka using its outbox event router (topic, key, and headers derived from row columns).
3. Outbox rows are deleted by Debezium config (`delete after publish`) — the WAL itself is the durable buffer.

```sql
CREATE TABLE outbox (
  id             UUID PRIMARY KEY,
  aggregate_type TEXT NOT NULL,      -- routes to topic
  aggregate_id   TEXT NOT NULL,      -- becomes Kafka key (orderId)
  event_type     TEXT NOT NULL,
  payload        BYTEA NOT NULL,     -- Avro-serialized
  headers        JSONB NOT NULL,     -- traceparent, correlationId, causationId
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Trace context is written into the `headers` column so distributed traces survive the asynchronous hop.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Dual write (status quo) | Loses or fabricates events under failure — the exact bug class we're building this project to demonstrate solving |
| Polling publisher (service polls its own outbox and publishes) | Works and is simpler operationally (no Debezium); rejected because polling adds latency-vs-load tension, competes with app resources, and needs careful `SELECT ... FOR UPDATE SKIP LOCKED` coordination across replicas. Documented as the fallback if operating Debezium proves too costly |
| Event sourcing (events *are* the state) | Solves atomicity elegantly but changes the entire programming model; too large a hammer when only the publish step needs fixing. Reconsider per-aggregate if temporal queries become a requirement |
| Kafka transactions + consume-process-produce | Only covers Kafka→Kafka flows; our triggering write is HTTP→Postgres |
| Listen-to-yourself (publish first, consume own event to update DB) | Inverts the problem (state lags events), confuses every read-after-write, and still isn't atomic |

## Consequences

**Positive**
- **No lost and no phantom events**, by construction — verified by a chaos test that kills the JVM between arbitrary statements (Testcontainers + Toxiproxy).
- Event publication adds **zero latency** to the user-facing transaction (relay is async).
- The outbox table doubles as a short-term local audit of everything a service emitted.

**Negative / accepted costs**
- **Debezium is another stateful component to operate** (Kafka Connect cluster, connector offsets, PostgreSQL replication slots). Mitigations: `outbox_lag_seconds` metric + alert; runbook section for connector restarts; replication slot monitoring (an abandoned slot can bloat the WAL — this is the sharpest operational edge, and it's documented).
- End-to-end publish latency is bound to CDC lag (p99 ≈ 40 ms in our setup) — acceptable for an inherently async flow.
- Events appear in Kafka *after* the transaction, so tests must await asynchronously (Awaitility) rather than assert immediately — a testing-style cost, covered in TESTING.md.
