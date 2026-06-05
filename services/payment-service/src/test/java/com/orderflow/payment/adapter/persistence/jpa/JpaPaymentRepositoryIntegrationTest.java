package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.exception.ConcurrencyConflictException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.*;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validação de ida-e-volta do adapter JPA contra um PostgreSQL real
 * (Testcontainers + Flyway). Cobre o contrato do port
 * {@link com.orderflow.payment.domain.repository.PaymentRepository}:
 * persistência atômica de estado + outbox, reidratação do agregado,
 * idempotência por orderId e concorrência otimista. Com
 * {@code ddl-auto=validate}, o teste também garante que o mapeamento das
 * entidades casa exatamente com o schema das migrações.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PersistenceConfiguration.class})
@DisplayName("JpaPaymentRepository — integração")
class JpaPaymentRepositoryIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("orderflow_payments")
            .withUsername("orderflow")
            .withPassword("orderflow");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private OutboxJpaSpringRepository outbox;

    @Test
    @DisplayName("save persiste estado e evento na outbox numa única transação")
    void savePersistsStateAndOutboxAtomically() {
        Payment payment = authorizedPayment();

        repository.save(payment, 0L);

        Optional<Payment> reloaded = repository.findById(payment.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(outbox.findAll())
                .filteredOn(e -> e.getAggregateId().equals(payment.id().value()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getAggregateType()).isEqualTo("Payment");
                    assertThat(e.getEventType()).isEqualTo("PaymentAuthorized");
                });
    }

    @Test
    @DisplayName("initiate sem eventos cria a linha PENDING (persistência tradicional)")
    void savePersistsPendingWithoutEvents() {
        Payment payment = Payment.initiate(
                PaymentId.generate(),
                OrderId.of(UUID.randomUUID()),
                CustomerId.of(UUID.randomUUID()),
                Money.of("30.00", "BRL"),
                PaymentMethod.PIX,
                CLOCK
        );

        repository.save(payment, 0L);

        Optional<Payment> reloaded = repository.findById(payment.id());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.get().version()).isZero();
        assertThat(outbox.findAll())
                .noneMatch(e -> e.getAggregateId().equals(payment.id().value()));
    }

    @Test
    @DisplayName("findById reidrata o agregado com montantes e versão preservados")
    void findByIdRehydratesAggregate() {
        Payment payment = authorizedPayment();
        repository.save(payment, 0L);

        Payment reloaded = repository.findById(payment.id()).orElseThrow();
        reloaded.capture();
        repository.save(reloaded, reloaded.version() - 1);

        Payment captured = repository.findById(payment.id()).orElseThrow();
        assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.capturedAmount().isEqualValue(payment.authorizedAmount())).isTrue();
        assertThat(captured.version()).isEqualTo(2L);
        assertThat(captured.gatewayTransactionId()).isEqualTo(GatewayTransactionId.of("ch_123"));
    }

    @Test
    @DisplayName("findByOrderId localiza o pagamento do pedido")
    void findByOrderIdLocatesPayment() {
        Payment payment = authorizedPayment();
        repository.save(payment, 0L);

        Optional<Payment> found = repository.findByOrderId(payment.orderId());
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(payment.id());
    }

    @Test
    @DisplayName("findById retorna vazio para pagamento inexistente")
    void findByIdReturnsEmptyForUnknown() {
        assertThat(repository.findById(PaymentId.generate())).isEmpty();
    }

    @Test
    @DisplayName("save com versão esperada divergente falha com ConcurrencyConflictException")
    void saveDetectsStaleExpectedVersion() {
        Payment payment = authorizedPayment();
        repository.save(payment, 0L);

        // Duas cargas concorrentes do mesmo agregado, ambas na versão 1.
        Payment first = repository.findById(payment.id()).orElseThrow();
        Payment second = repository.findById(payment.id()).orElseThrow();

        // A primeira captura e grava com sucesso, avançando a versão para 2.
        first.capture();
        repository.save(first, 1L);

        // A segunda, ainda na versão 1, perde a corrida.
        second.capture();
        assertThatThrownBy(() -> repository.save(second, 1L))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    private static Payment authorizedPayment() {
        Payment payment = Payment.initiate(
                PaymentId.generate(),
                OrderId.of(UUID.randomUUID()),
                CustomerId.of(UUID.randomUUID()),
                Money.of("100.00", "BRL"),
                PaymentMethod.CREDIT_CARD,
                CLOCK
        );
        payment.authorize(GatewayTransactionId.of("ch_123"), AuthorizationCode.of("A1B2C3"));
        return payment;
    }
}