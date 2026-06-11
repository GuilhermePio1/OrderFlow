# ADR-0002: Saga Pattern — Orchestration over Choreography

- **Status:** Accepted
- **Date:** 2026-06-02
- **Deciders:** José Guilherme (author)

## Context

An order spans four services that must either all succeed or be consistently rolled back: authorize payment, reserve stock, schedule shipment. Two-phase commit is off the table (no XA across Kafka + four PostgreSQL instances + an external PSP, and 2PC's blocking behavior is operationally unacceptable). The standard answer is the **Saga pattern**: a sequence of local transactions, each with a compensating action.

Sagas come in two flavors:

- **Choreography:** each service listens for events and reacts; the flow is emergent. No central coordinator.
- **Orchestration:** one component (the orchestrator) explicitly commands each step and reacts to replies; the flow is a state machine.

## Decision

Use **orchestration**, with the saga orchestrator living inside `order-service` as a persisted state machine (`saga_instance` table, advanced only by incoming events, partitioned by `orderId`).

Reasons:

1. **The flow is visible in one place.** The entire order lifecycle — including all compensation branches and timeouts — is readable in a single state-machine definition. With choreography, the flow exists only in the union of five services' consumer code, which is where production debugging goes to die.
2. **Compensation logic is centralized.** "Stock rejected → refund payment" is one transition in the orchestrator, not a chain of events each service must know to react to. Adding a step (e.g., fraud check) touches the orchestrator + the new service, not every participant.
3. **Timeout handling has an owner.** Someone must decide that a step took too long and trigger compensation. In choreography this requires every participant to implement timers; in orchestration it's a single scheduler.
4. **Cyclic event dependencies are avoided.** Choreographed sagas with compensation famously develop event cycles (payment listens to inventory listens to shipping listens to payment...) that make the system impossible to reason about.

The participants remain **dumb and autonomous**: payment-service knows how to authorize and refund; it knows nothing about inventory or shipping. Coupling is to message contracts, not to the orchestrator's existence.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Choreography | Acceptable for 2–3 steps with no compensation; our flow has 3 steps × compensation × timeouts = emergent complexity nobody can trace. Kept for pure fan-out concerns (notifications) where it shines |
| 2PC / XA transactions | Blocking, coordinator SPOF, unsupported across Kafka + PSP; widely considered an anti-pattern in microservices |
| Workflow engine (Temporal, Camunda) | Genuinely strong option and the right call at higher saga complexity. Rejected here to (a) demonstrate the pattern from first principles — the point of a portfolio project — and (b) avoid a heavyweight dependency for a 3-step saga. Decision explicitly revisitable; the orchestrator's interface was designed so Temporal could replace it without touching participants |
| Orchestrator as a separate microservice | Pure deployment overhead at this scale; the orchestrator shares the Order aggregate's data and lifecycle. Split if/when other sagas (returns, exchanges) appear |

## Consequences

**Positive**
- One readable state machine; new-engineer onboarding to the order flow takes minutes.
- Deterministic, testable compensation paths (state machine is unit-tested exhaustively, including late-reply-after-timeout races).
- Saga state is queryable (`GET /orders/{id}` shows exactly where an order is stuck), powering the on-call runbook.

**Negative / accepted costs**
- `order-service` is a logical coordination point — if it's down, *new* sagas don't start and replies queue up. Mitigated: it's stateless-horizontal (state in DB/Kafka), runs ≥3 replicas, and queued replies are processed on recovery with zero loss.
- Risk of the orchestrator accumulating business logic that belongs in participants ("god service" drift). Guarded by a hard rule: the orchestrator routes and compensates, it never decides business outcomes (e.g., it never re-checks stock levels — that's inventory's job).
- A persisted state machine + timeout scheduler is non-trivial code (~600 LOC + tests). Accepted: it is the centerpiece of the project.
