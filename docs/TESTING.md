# OrderFlow Testing Strategy

The hard part of testing an event-driven system is that the interesting failures are *asynchronous and distributed*: duplicates, reordering, crashes between steps, slow consumers. The test suite is designed around those, not just around happy-path coverage.

## Test Pyramid

| Layer | Scope | Tooling | Count (approx) | Runtime |
|---|---|---|---|---|
| Unit | Aggregates, saga state machine, mappers | JUnit 5, AssertJ | ~400 | < 10 s |
| Integration | One service + real Postgres/Kafka | Testcontainers, Awaitility | ~120 | ~4 min |
| Contract | Event schemas + consumer expectations | Schema Registry compat check + spring-cloud-contract | per schema | < 1 min |
| End-to-end | Full saga across all services | Docker Compose profile, scenario runner | 12 scenarios | ~6 min |
| Chaos | Crash/duplicate/partition injection | Toxiproxy, custom kill harness | 8 scenarios | ~10 min (nightly) |
| Load | Throughput/latency under sustained load | Gatling | 3 simulations | nightly |
| Architecture | Dependency & convention rules | ArchUnit | ~15 rules | < 5 s |

CI runs unit + integration + contract + architecture on every PR; e2e on merge to main; chaos + load nightly with trend dashboards.

## 1. Unit: the saga state machine is exhaustively tested

The orchestrator is pure (state + event → new state + commands), which makes it ideal unit-test material. Every transition in the state diagram has a test, plus the nasty races:

```java
@Test
void lateReplyAfterTimeoutIsDiscarded() {
    var saga = sagaInState(AWAITING_PAYMENT, version = 3);
    saga.handle(stepTimeout(AWAITING_PAYMENT, version = 3));   // → compensating
    var result = saga.handle(paymentApproved(version = 3));    // late reply arrives

    assertThat(result.commands()).isEmpty();                   // discarded, not double-advanced
    assertThat(saga.state()).isEqualTo(COMPENSATING);
    assertThat(result.audit()).contains(LATE_REPLY_DISCARDED); // and it's observable
}
```

## 2. Integration: real infrastructure, no mocks of what matters

Each service has a `*IntegrationTest` suite against **real PostgreSQL and Kafka via Testcontainers** (singleton containers reused across test classes to keep runtime sane). Mocking Kafka or JPA would test our assumptions instead of our code.

```java
@SpringBootTest
@Testcontainers
class PaymentSagaIntegrationTest {

    @Container static KafkaContainer kafka = Containers.kafka();        // shared singletons
    @Container static PostgreSQLContainer<?> db = Containers.postgres();

    @Test
    void duplicateAuthorizeCommandChargesOnce() {
        var cmd = authorizePaymentCommand(orderId);

        publish(cmd);
        publish(cmd);   // exact duplicate, same eventId

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(consumedEvents(PaymentApproved.class, orderId)).hasSize(1);
            assertThat(pspStub.authorizationCalls(orderId)).isEqualTo(1); // money moved once
        });
    }
}
```

Conventions for async assertions: **Awaitility everywhere, zero `Thread.sleep`** (banned by an ArchUnit rule), generous timeouts with tight polling, and assertions on *terminal* facts (DB rows, emitted events), never on intermediate timing.

The outbox path is integration-tested with a real Debezium container — the test asserts that a service-level transaction rollback produces **no** Kafka event, and a commit produces exactly one with correct key/headers.

## 3. Contract: schemas are the API

- Every PR touching `common/events` runs `checkSchemaCompatibility` against a registry container in `BACKWARD` mode — incompatible evolution fails CI before review.
- Consumer-driven expectations (which fields each consumer actually reads) are recorded as spring-cloud-contract stubs, so removing a field "nobody uses" fails the build of the consumer that does.

## 4. End-to-end scenarios

`demo/run-scenarios.sh` drives the composed stack through 12 scripted scenarios; CI asserts terminal order state, emitted event sequence, and invariant checks (reconciliation mini-run). The scenario list *is* the behavioral spec:

1. Happy path → `COMPLETED`
2. Payment declined (non-retryable) → `CANCELLED(PAYMENT_DECLINED)`, no stock touched
3. Payment PSP_ERROR ×2 then success → `COMPLETED` (retry policy)
4. Out of stock → refund → `CANCELLED(OUT_OF_STOCK)`
5. Shipment failed → release + refund → `CANCELLED(SHIPMENT_FAILED)`
6. Customer cancels during `PENDING` → `CANCELLED`, no compensation needed
7. Customer cancels during `PAYMENT_APPROVED` → refund → `CANCELLED`
8. Customer cancel rejected at `STOCK_RESERVED`+ → `409`, order proceeds
9. Idempotency-Key replay → single order, `200` on second POST
10. Duplicate event injection at every step → identical end state
11. Step timeout (inventory paused > 30 s) → compensation; late reply discarded
12. Reservation TTL expiry (saga killed silently) → auto-release job frees stock

## 5. Chaos suite (nightly)

Built on Toxiproxy (network faults) and a kill harness (SIGKILL at instrumented points):

- **Kill-between-statements:** JVM killed between DB commit and "would-be publish" → outbox guarantees the event still flows (this is ADR-0003's proof).
- **Broker failover:** kill the partition leader mid-load → zero lost orders (reconciliation check), bounded latency spike recorded.
- **Debezium outage 10 min under load** → no loss, lag alert fires, full drain after restart; drain time recorded as a trend metric.
- **Consumer crash-loop on poison message** → message lands in DLT after policy, lag does not grow unboundedly, replay restores flow.
- **Network partition payment↔PSP stub** → circuit opens, saga retries then compensates per policy.

Each chaos scenario asserts three things: **no invariant violated** (money/stock), **the right alert fired**, and **the system returned to steady state without human action** (except where the runbook says a human is required — then the assertion is that the DLQ/page happened).

## 6. Load testing

Gatling simulations in `load-tests/`:

| Simulation | Profile | Pass criteria |
|---|---|---|
| `SteadyStateSimulation` | 1h at 3,000 orders/s | saga p99 < 800 ms, zero errors, lag bounded |
| `SpikeSimulation` | 10× spike for 60 s | accept-latency p99 < 100 ms, recovery < 2 min |
| `SoakSimulation` | 8h at 1,500 orders/s | no memory growth trend, no lag trend |

Methodology notes (because numbers without methodology are marketing): closed-model injection, separate load-gen host, warm-up excluded, results include full percentile distributions and the exact hardware/topology, committed under `load-tests/results/` per run. Headline numbers in the README come from `results/2026-05-30/`.

## 7. Architecture tests (ArchUnit)

The rules that keep the design honest, enforced in CI:

- Saga handlers may not call `RestClient`/`WebClient` outside designated ports (idempotency boundary, ADR-0004).
- No service module may depend on another service's module; only on `common.events` and `common.messaging`.
- `Thread.sleep` is forbidden in `src/test`.
- All `@KafkaListener` methods must be annotated `@IdempotentHandler`.
- Money fields must be `long` cents — no `double`, no `float`, anywhere (custom rule scanning field types on `*Event`, `*Command`, entities).
