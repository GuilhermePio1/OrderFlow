# ADR-0005: Database per Service

- **Status:** Accepted
- **Date:** 2026-06-10
- **Deciders:** José Guilherme (author)

## Context

With services split, the data layout must be decided: one shared database (simple, familiar, transactional joins) versus a private database per service (autonomous, decoupled, but no cross-service queries or transactions). This decision determines whether the services are actually independent or merely a distributed monolith.

## Decision

**Each service owns a private PostgreSQL database.** No service may read or write another service's tables — there are separate credentials per database, so the rule is enforced by the database, not by convention. All cross-service data flow happens through events (Kafka) or, exceptionally, through the owner's API.

Schema management: each service carries its own Flyway migrations, applied on deploy. Schemas evolve independently; the only shared contracts are the Avro event schemas (governed by the Schema Registry, see EVENT_CATALOG.md).

We deliberately use **separate databases on a shared PostgreSQL cluster** locally (and separate instances in the k8s manifests) — logical isolation is the architectural requirement; physical isolation is a deployment/scaling choice.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Shared database, shared schema | The classic distributed monolith: any schema change is a lockstep deploy across services; "just one join" across contexts inevitably appears; one service's hot query degrades everyone. Eliminates the main benefit of having services at all |
| Shared database, schema-per-service | Better, and a legitimate stepping stone in migrations — but credentials/discipline tend to erode, and resource contention (connections, vacuum, locks) remains shared fate |
| Polyglot persistence (Redis for inventory, Mongo for orders...) | Tempting for a portfolio, but unjustified: every workload here is relational and modest. Choosing tech the problem doesn't need is itself a senior-level smell. PostgreSQL everywhere keeps operations simple; the door stays open per-service precisely *because* databases are private |

## Consequences

**Positive**
- True independent deployability: a payment schema migration cannot break order queries.
- Failure isolation: a runaway query in inventory cannot starve payments.
- Each service's data model fits its context (inventory uses optimistic-locking-friendly narrow rows; orders keep a state-history table) without negotiation.

**Negative / accepted costs**
- **No cross-service joins.** "Orders with payment details" requires composition at the edge (gateway/API composition) or a read model. At current scale, API composition suffices; a dedicated CQRS read-model service is the documented next step if read traffic dominates (kept out per YAGNI — and to keep this project's scope honest).
- **No cross-service transactions** — which is exactly why the saga (ADR-0002) and outbox (ADR-0003) exist; this ADR is the root cause of both.
- **Reporting/analytics** can't query one big database. Solved idiomatically: the compacted state topics in Kafka are the analytics feed (future Kafka Streams / warehouse sink), not production databases.
- More infrastructure to provision and back up. Mitigated with identical, templated Postgres deployment manifests.
