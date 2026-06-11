# ADR-0004: Idempotent Consumers over Exactly-Once Delivery

- **Status:** Accepted
- **Date:** 2026-06-07
- **Deciders:** José Guilherme (author)

## Context

Our delivery pipeline is at-least-once at every hop: Debezium may re-publish after a connector restart; Kafka redelivers on consumer rebalance or commit failure; the saga orchestrator may resend a command after a timeout that the participant actually processed. Therefore **every consumer will eventually receive duplicates.** A duplicated `AuthorizePaymentCommand` that charges a customer twice is not a theoretical concern — it is the default behavior of naive code.

"Exactly-once" marketing aside, Kafka EOS only covers consume-transform-produce *within* Kafka. Our side effects (PostgreSQL writes, PSP calls, emails) are outside Kafka, so end-to-end exactly-once delivery is unattainable. What is attainable is **effectively-once processing**: duplicates arrive but have no effect.

## Decision

Every consumer implements idempotency via a **processed-messages table checked inside the consumer's local transaction**:

```sql
CREATE TABLE processed_message (
  consumer_group TEXT NOT NULL,
  event_id       UUID NOT NULL,
  processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_group, event_id)
);
```

Processing algorithm (wrapped in a shared `@IdempotentHandler` aspect in `common/messaging`):

1. Begin local DB transaction.
2. `INSERT INTO processed_message ...` — a duplicate violates the PK → **skip processing, ack the record**, done.
3. Execute the business logic (state change + outbox row for the reply event).
4. Commit. Offset is committed only after the transaction succeeds.

Because the dedup insert, the state change, and the reply's outbox row share one ACID transaction, the outcome is binary: either the message is fully processed exactly once, or it has no effect and will be redelivered.

Two reinforcements:

- **Natural-key idempotency where it matters most:** payment-service additionally enforces `UNIQUE(order_id)` on authorizations, and the PSP call sends `orderId` as the PSP idempotency key — so even a bug in the dedup layer cannot double-charge.
- **Retention:** `processed_message` rows are pruned after 14 days (> max Kafka retention of the source topics, so a redelivery can never outlive its dedup record).

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Kafka EOS / transactions end-to-end | Doesn't extend to PostgreSQL or the PSP; gives false confidence precisely where the money moves |
| Dedup in Redis with TTL | Fast, but the check is **not atomic with the business transaction** — a crash between Redis SET and DB commit silently drops a message. Correctness over speed |
| Naturally idempotent operations only ("set status = X") | Works for pure state-set operations, but `reserve 2 units` and `send email` are not naturally idempotent; a uniform mechanism is easier to audit than per-handler cleverness |
| Broker-side deduplication (e.g., SQS FIFO dedup window) | Ties correctness to a 5-minute window and a specific vendor; redeliveries beyond the window still get through |

## Consequences

**Positive**
- Duplicates are harmless by construction; verified by a dedicated test that replays every saga message twice (and the whole topic once) and asserts identical end state — including PSP stub call counts.
- Redelivery becomes an operational *tool* (DLT replay, topic re-consumption for reconciliation) instead of a hazard.

**Negative / accepted costs**
- One extra indexed insert per message (~3% throughput cost in our benchmarks) and a table to prune. Cheap insurance.
- The pattern requires discipline: a handler that performs side effects *outside* the transaction (HTTP call to PSP) needs its own idempotency key downstream — hence the PSP natural-key reinforcement. This rule is enforced in code review via an architecture test (ArchUnit: handlers may not use `RestClient` outside designated ports).
