package com.orderflow.order.adapter.persistence.r2dbc;

import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.exception.ConcurrencyConflictException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.repository.OrderRepository;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter R2DBC do port {@link OrderRepository}.
 *
 * Escrita: numa única transação, persiste os eventos pendentes do agregado
 * no event store e na tabela outbox. Concorrência otimista é aplicada de
 * duas formas complementares — pré-checagem da versão corrente e a unique
 * constraint {@code uq_events_aggregate_sequence}, que protege contra a
 * race window entre o SELECT e o INSERT.
 *
 * Leitura: carrega o stream ordenado de eventos do agregado e delega a
 * reidratação ao próprio agregado via {@link Order#loadFromHistory}.
 *
 * Snapshots (V3) ainda não são lidos nem escritos por este adapter; a
 * tabela existe como infraestrutura preparada para uma futura otimização
 * de leitura, conforme {@code docs/event-sourcing.md}.
 */
@Repository
class R2dbcOrderRepository implements OrderRepository {

    private static final String AGGREGATE_TYPE = "Order";
    private static final Json EMPTY_JSON_OBJECT = Json.of("{}");

    private final DatabaseClient client;
    private final TransactionalOperator tx;
    private final OrderEventCodec codec;
    private final Clock clock;

    R2dbcOrderRepository(
            DatabaseClient client,
            TransactionalOperator tx,
            OrderEventCodec codec,
            Clock clock
    ) {
        this.client = client;
        this.tx = tx;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public Mono<Order> findById(OrderId orderId) {
        UUID aggregateId = orderId.value();
        return loadStream(aggregateId)
                .collectList()
                .flatMap(history -> {
                    if (history.isEmpty()) {
                        return Mono.error(new OrderNotFoundException(orderId));
                    }
                    return Mono.fromCallable(() -> Order.loadFromHistory(history, clock));
                });
    }

    @Override
    public Mono<Void> save(Order order, long expectedVersion) {
        List<OrderEvent> pending = order.pullUncommittedEvents();
        if (pending.isEmpty()) {
            return Mono.empty();
        }
        OrderId orderId = order.id();
        UUID aggregateId = orderId.value();

        Mono<Void> work = currentVersion(aggregateId)
                .flatMap(actual -> {
                    if (actual != expectedVersion) {
                        return Mono.error(new ConcurrencyConflictException(
                                orderId, expectedVersion, actual));
                    }
                    return persistEvents(aggregateId, expectedVersion, pending);
                });

        return tx.transactional(work)
                .onErrorMap(R2dbcDataIntegrityViolationException.class,
                        ex -> new ConcurrencyConflictException(orderId, expectedVersion, -1));
    }

    // ---------- reads ----------

    private Flux<OrderEvent> loadStream(UUID aggregateId) {
        return client.sql("""
                        SELECT event_type, payload
                            FROM events
                        WHERE aggregate_id = :aggregateId
                        ORDER BY sequence_number
                        """)
                .bind("aggregateId", aggregateId)
                .map((row, meta) -> codec.deserialize(
                        row.get("event_type", String.class),
                        Objects.requireNonNull(row.get("payload", Json.class)).asString()))
                .all();
    }

    private Mono<Long> currentVersion(UUID aggregateId) {
        return client.sql("""
                        SELECT COALESCE(MAX(sequence_number), 0) AS version
                            FROM events
                        WHERE aggregate_id = :aggregateId
                        """)
                .bind("aggregateId", aggregateId)
                .map((row, meta) -> {
                    Long v = row.get("version", Long.class);
                    return v == null ? 0 : v;
                })
                .one()
                .defaultIfEmpty(0L);
    }

    // ---------- writes ----------

    private Mono<Void> persistEvents(UUID aggregateId, long expectedVersion, List<OrderEvent> events) {
        return Flux.range(0, events.size())
                .concatMap(i -> {
                    OrderEvent event = events.get(i);
                    long sequenceNumber = expectedVersion + i + 1;
                    String eventType = codec.eventTypeOf(event);
                    Json payload = Json.of(codec.serialize(event));
                    return insertEvent(aggregateId, sequenceNumber, eventType, payload, event)
                            .then(insertOutbox(aggregateId, eventType, payload, event));
                })
                .then();
    }

    private Mono<Void> insertEvent(
            UUID aggregateId,
            long sequenceNumber,
            String eventType,
            Json payload,
            OrderEvent event
    ) {
        return client.sql("""
                        INSERT INTO events (
                            event_id, aggregate_id, aggregate_type, event_type,
                            event_version, sequence_number, payload, metadata, occurred_at
                        )
                        VALUES (
                            :eventId, :aggregateId, :aggregateType, :eventType,
                            :eventVersion, :sequenceNumber, :payload, :metadata, :occurredAt
                        )
                        """)
                .bind("eventId", event.eventId())
                .bind("aggregateId", aggregateId)
                .bind("aggregateType", AGGREGATE_TYPE)
                .bind("eventType", eventType)
                .bind("eventVersion", event.schemaVersion())
                .bind("sequenceNumber", sequenceNumber)
                .bind("payload", payload)
                .bind("metadata", EMPTY_JSON_OBJECT)
                .bind("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> insertOutbox(
            UUID aggregateId,
            String eventType,
            Json payload,
            OrderEvent event
    ) {
        return client.sql("""
                          INSERT INTO outbox (
                              id, aggregate_id, aggregate_type, event_type,
                              event_id, payload, headers
                          )
                          VALUES (
                              :id, :aggregateId, :aggregateType, :eventType,
                              :eventId, :payload, :headers
                          )
                          """)
                .bind("id", UUID.randomUUID())
                .bind("aggregateId", aggregateId)
                .bind("aggregateType", AGGREGATE_TYPE)
                .bind("eventType", eventType)
                .bind("eventId", event.eventId())
                .bind("payload", payload)
                .bind("headers", EMPTY_JSON_OBJECT)
                .fetch()
                .rowsUpdated()
                .then();
    }
}
