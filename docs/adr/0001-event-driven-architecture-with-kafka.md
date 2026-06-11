# ADR-0001: Event-Driven Architecture with Apache Kafka

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** José Guilherme (author)

## Context

Order processing spans multiple bounded contexts (orders, payments, inventory, shipping, notifications) with different scaling profiles and failure characteristics. A synchronous REST call chain (`order → payment → inventory → shipping`) couples the availability of the whole flow to its weakest service: if shipping is down, customers cannot place orders at all, even though shipment scheduling could safely happen minutes later.

We need: temporal decoupling (services can be down without losing work), per-order ordering guarantees, replayability for recovery and reconciliation, and horizontal scalability.

## Decision

Use **asynchronous, event-driven communication over Apache Kafka** as the backbone between services. Synchronous REST remains only at the edge, where the client needs an immediate accept/reject answer.

Kafka over RabbitMQ/SQS because:

1. **Replayable log, not just a queue.** Offsets + retention let us re-consume history — this powers the reconciliation job, DLT replay, and rebuilding read models. Brokers with destructive consumption can't do this.
2. **Partition-key ordering.** Keying by `orderId` gives per-order ordering with global parallelism — exactly the granularity a saga needs. RabbitMQ requires consistent-hash exchange gymnastics for the same property.
3. **Throughput headroom.** Kafka's sequential-I/O design comfortably exceeds our 10× growth target on modest hardware.
4. **Ecosystem fit.** Debezium (outbox relay, ADR-0003), Schema Registry (contract enforcement), and Kafka Streams (future analytics) all build on it.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Synchronous REST chain + retries | Availability coupling; retry storms under partial outage; no natural audit log; distributed transaction problem remains unsolved |
| RabbitMQ | Excellent broker, but no replay, weaker ordering story, and we'd still need a separate log for reconciliation |
| AWS SQS/SNS | Vendor lock-in for a portfolio reference project; FIFO queues cap throughput per message group; no compaction/replay semantics |
| Shared database integration | Anti-pattern: schema coupling, no autonomy, accidental joins across contexts |

## Consequences

**Positive**
- Services are deployable and failable independently; work queues up instead of being lost.
- Complete, ordered event history per order — audit and debugging are dramatically simpler.
- Natural backpressure: consumer lag is visible and alertable, rather than manifesting as cascading timeouts.

**Negative / accepted costs**
- **Eventual consistency** is now a product-level concept: the API returns `202`, and clients must poll or use webhooks. Documented in the API reference.
- **Operational complexity:** Kafka + Schema Registry + Debezium must be operated and monitored. Mitigated with dashboards/alerts shipped in-repo (OPERATIONS.md).
- **Duplicate delivery is guaranteed eventually** — at-least-once everywhere forces idempotent consumers (ADR-0004). This is a cost we'd pay with any broker; Kafka just makes it explicit.
- Local development needs Docker Compose with ~6 services of infrastructure. Mitigated with a one-command setup.
