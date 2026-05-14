package com.orderflow.order.adapter.persistence.r2dbc;

import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.event.OrderPlaced;
import com.orderflow.order.domain.exception.ConcurrencyConflictException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.OrderStatus;
import com.orderflow.order.domain.model.valueobject.*;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validação de ida-e-volta contra um PostgreSQL real (Testcontainers).
 * Cobre o contrato do port {@link com.orderflow.order.domain.repository.OrderRepository}:
 * persistência atômica em events + outbox, concorrência otimista,
 * reidratação do agregado a partir do stream, e tratamento de findById
 * para agregado inexistente.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("R2dbcOrderRepository — integração")
class R2dbcOrderRepositoryIntegrationTest {

    private static final Instant FIXED_NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", "Apto 12", "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("orderflow_orders")
            .withUsername("orderflow")
            .withPassword("orderflow")
            .withReuse(false);

    private DatabaseClient databaseClient;
    private R2dbcOrderRepository repository;

    @BeforeAll
    void startContainerAndMigrate() {
        POSTGRES.start();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        var connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                .option(ConnectionFactoryOptions.PORT, POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                .build());

        this.databaseClient = DatabaseClient.create(connectionFactory);
        var txOperator = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        var codec = OrderEventCodec.withDefaultObjectMapper();
        this.repository = new R2dbcOrderRepository(databaseClient, txOperator, codec, CLOCK);

        databaseClient.sql("TRUNCATE outbox, events, snapshots RESTART IDENTITY").fetch().rowsUpdated().block();
    }

    @Test
    @DisplayName("save grava evento no event store e na outbox em uma única transação")
    void savePersistsEventAndOutboxAtomically() {
        Order order = newOrder();

        StepVerifier.create(repository.save(order, 0L))
                .verifyComplete();

        Long eventCount = databaseClient.sql("SELECT count(*) FROM events WHERE aggregate_id = $1")
                .bind("$1", order.id().value())
                .map((row, meta) -> row.get(0, Long.class))
                .one().block();
        Long outboxCount = databaseClient.sql("SELECT count(*) FROM outbox WHERE aggregate_id = $1")
                .bind("$1", order.id().value())
                .map((row, meta) -> row.get(0, Long.class))
                .one().block();

        assertThat(eventCount).isEqualTo(1L);
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("findById reidrata o agregado a partir do stream de eventos")
    void findByIdRehydratesAggregateFromEventStream() {
        Order order = newOrder();
        repository.save(order, 0L).block();

        Order rehydrated = repository.findById(order.id()).block();

        assertThat(rehydrated).isNotNull();
        assertThat(rehydrated.id()).isEqualTo(order.id());
        assertThat(rehydrated.customerId()).isEqualTo(order.customerId());
        assertThat(rehydrated.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(rehydrated.items()).hasSize(order.items().size());
        assertThat(rehydrated.totalAmount()).isEqualTo(order.totalAmount());
        assertThat(rehydrated.version()).isEqualTo(1L);
        assertThat(rehydrated.hasUncommittedEvents()).isFalse();
    }

    @Test
    @DisplayName("findById propaga OrderNotFoundException quando não há eventos")
    void findByIdFailsForUnknownAggregate() {
        StepVerifier.create(repository.findById(OrderId.generate()))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("save com versão divergente falha com ConcurrencyConflictException")
    void saveDetectsStaleExpectedVersion() {
        Order order = newOrder();
        repository.save(order, 0L).block();

        Order rehydrated = repository.findById(order.id()).block();
        assertThat(rehydrated).isNotNull();
        rehydrated.cancel(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, "test");

        StepVerifier.create(repository.save(rehydrated, 0L))
                .expectError(ConcurrencyConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("save sequencia eventos a partir da versão esperada")
    void saveAppendsEventsWithMonotonicSequenceNumbers() {
        Order order = newOrder();
        repository.save(order, 0L).block();

        Order rehydrated = repository.findById(order.id()).block();
        assertThat(rehydrated).isNotNull();
        rehydrated.confirmPayment(UUID.randomUUID());
        rehydrated.reserveInventory(UUID.randomUUID());

        repository.save(rehydrated, 1L).block();

        List<Long> sequenceNumbers = databaseClient.sql(
                "SELECT sequence_number FROM events WHERE aggregate_id = $1 ORDER BY sequence_number")
                .bind("$1", order.id().value())
                .map((row, meta) -> row.get(0, Long.class))
                .all().collectList().block();

        // OrderPlaced (1), OrderPaymentConfirmed (2), OrderInventoryReserved (3),
        // OrderConfirmed emitido quando ambos satisfazem (4).
        assertThat(sequenceNumbers).containsExactly(1L, 2L, 3L, 4L);

        Order finalState = repository.findById(order.id()).block();
        assertThat(finalState).isNotNull();
        assertThat(finalState.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(finalState.version()).isEqualTo(4L);
    }

    @Test
    @DisplayName("save sem eventos pendentes é no-op")
    void saveIsNoOpWhenAggregateHasNoUncommittedEvents() {
        Order order = newOrder();
        repository.save(order, 0L).block();

        Order rehydrated = repository.findById(order.id()).block();
        assertThat(rehydrated).isNotNull();
        assertThat(rehydrated.hasUncommittedEvents()).isFalse();

        StepVerifier.create(repository.save(rehydrated, 1L))
                .verifyComplete();
    }

    @Test
    @DisplayName("payload do evento preserva os campos originais para projeções a jusante")
    void payloadRoundTripsThroughJsonStorage() {
        Order order = newOrder();
        repository.save(order, 0L).block();

        Order rehydrated = repository.findById(order.id()).block();
        assertThat(rehydrated).isNotNull();

        List<OrderEvent> uncommitted = rehydrated.pullUncommittedEvents();
        assertThat(uncommitted).isEmpty();

        OrderItem firstItem = rehydrated.items().getFirst();
        OrderItem original = order.items().getFirst();
        assertThat(firstItem.productId()).isEqualTo(original.productId());
        assertThat(firstItem.quantity()).isEqualTo(original.quantity());
        assertThat(firstItem.unitPrice()).isEqualTo(original.unitPrice());
    }

    @Test
    @DisplayName("OrderPlaced é o evento gravado quando o pedido é criado")
    void orderPlacedIsPersisted() {
        Order order = newOrder();
        repository.save(order, 0L).block();

        String eventType = databaseClient.sql(
                "SELECT event_type FROM events WHERE aggregate_id = $1 ORDER BY sequence_number LIMIT 1")
                .bind("$1", order.id().value())
                .map((row, meta) -> row.get(0, String.class))
                .one().block();

        assertThat(eventType).isEqualTo("OrderPlaced");
        // Confirma também que o tipo está registrado no codec.
        assertThat(OrderPlaced.class).isNotNull();
    }

    private static Order newOrder() {
        return Order.place(
                OrderId.generate(),
                CustomerId.of(UUID.randomUUID()),
                List.of(
                        new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(2), Money.of("10.00", "BRL")),
                        new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(1), Money.of("5.00", "BRL"))
                ),
                ADDRESS,
                CLOCK
        );
    }
}
