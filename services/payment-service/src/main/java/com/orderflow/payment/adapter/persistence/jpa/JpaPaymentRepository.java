package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.event.PaymentEvent;
import com.orderflow.payment.domain.exception.ConcurrencyConflictException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.*;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter JPA do port {@link PaymentRepository}.
 *
 * Escrita: numa única transação, persiste o estado atual do agregado em
 * {@code payments} e os eventos pendentes em {@code outbox} (padrão Outbox).
 * A concorrência otimista é aplicada de duas formas complementares — o INSERT
 * de criação é protegido pela unique constraint, e a atualização por um UPDATE
 * condicional guardado por {@code expectedVersion} ({@link PaymentJpaSpringRepository#updateState}),
 * que fecha a race window entre a leitura da versão e a escrita.
 *
 * Bloqueante por escolha: o contexto Payment usa Spring MVC com virtual
 * threads e privilegia clareza transacional (ver {@code docs/architecture.md}).
 */
class JpaPaymentRepository implements PaymentRepository {

    private static final String AGGREGATE_TYPE = "Payment";
    private static final String EMPTY_JSON_OBJECT = "{}";

    private final PaymentJpaSpringRepository payments;
    private final OutboxJpaSpringRepository outbox;
    private final PaymentEventCodec codec;
    private final Clock clock;

    JpaPaymentRepository(
            PaymentJpaSpringRepository payments,
            OutboxJpaSpringRepository outbox,
            PaymentEventCodec codec,
            Clock clock
    ) {
        this.payments = payments;
        this.outbox = outbox;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findById(PaymentId paymentId) {
        return payments.findById(paymentId.value()).map(this::rehydrate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(OrderId orderId) {
        return payments.findByOrderId(orderId.value()).map(this::rehydrate);
    }

    @Override
    @Transactional
    public void save(Payment payment, long expectedVersion) {
        List<PaymentEvent> pending = payment.pullUncommittedEvents();
        UUID id = payment.id().value();

        if (payments.existsById(id)) {
            updateExisting(payment, expectedVersion);
        } else {
            insertNew(payment, expectedVersion);
        }

        for (PaymentEvent event : pending) {
            outbox.save(toOutbox(id, event));
        }
    }

    // ---------- writes ----------

    private void insertNew(Payment payment, long expectedVersion) {
        if (expectedVersion != 0) {
            // Esperava-se uma linha existente, mas não há nenhuma: estado obsoleto.
            throw new ConcurrencyConflictException(payment.id(), expectedVersion, 0);
        }
        try {
            payments.saveAndFlush(toEntity(payment));
        } catch (DataIntegrityViolationException ex) {
            // Criação concorrente para o mesmo pagamento/pedido (unique constraint).
            throw new ConcurrencyConflictException(payment.id(), expectedVersion, -1);
        }
    }

    private void updateExisting(Payment payment, long expectedVersion) {
        GatewayTransactionId gatewayTransactionId = payment.gatewayTransactionId();
        AuthorizationCode authorizationCode = payment.authorizationCode();

        int updated = payments.updateState(
                payment.id().value(),
                expectedVersion,
                payment.version(),
                payment.status(),
                gatewayTransactionId == null ? null : gatewayTransactionId.value(),
                authorizationCode == null ? null : authorizationCode.value(),
                payment.capturedAmount().amount(),
                payment.refundedAmount().amount(),
                payment.failureReason(),
                payment.failureDetails()
        );
        if (updated == 0) {
            long actual = payments.findById(payment.id().value())
                    .map(PaymentJpaEntity::getVersion)
                    .orElse(-1L);
            throw new ConcurrencyConflictException(payment.id(), expectedVersion, actual);
        }
    }

    private PaymentJpaEntity toEntity(Payment payment) {
        GatewayTransactionId gatewayTransactionId = payment.gatewayTransactionId();
        AuthorizationCode authorizationCode = payment.authorizationCode();
        Money authorized = payment.authorizedAmount();
        return new PaymentJpaEntity(
                payment.id().value(),
                payment.orderId().value(),
                payment.customerId().value(),
                authorized.amount(),
                payment.capturedAmount().amount(),
                payment.refundedAmount().amount(),
                authorized.currency().getCurrencyCode(),
                payment.method(),
                payment.status(),
                gatewayTransactionId == null ? null : gatewayTransactionId.value(),
                authorizationCode == null ? null : authorizationCode.value(),
                payment.failureReason(),
                payment.failureDetails(),
                payment.initiatedAt(),
                payment.version()
        );
    }

    private OutboxJpaEntity toOutbox(UUID aggregateId, PaymentEvent event) {
        return new OutboxJpaEntity(
                UUID.randomUUID(),
                aggregateId,
                AGGREGATE_TYPE,
                codec.eventTypeOf(event),
                event.eventId(),
                codec.serialize(event),
                EMPTY_JSON_OBJECT,
                Instant.now(clock)
        );
    }

    // ---------- reads ----------

    private Payment rehydrate(PaymentJpaEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        Payment.Snapshot snapshot = new Payment.Snapshot(
                PaymentId.of(entity.getId()),
                OrderId.of(entity.getOrderId()),
                CustomerId.of(entity.getCustomerId()),
                Money.of(entity.getAuthorizedAmount(), currency),
                Money.of(entity.getCapturedAmount(), currency),
                Money.of(entity.getRefundedAmount(), currency),
                entity.getMethod(),
                entity.getStatus(),
                gatewayTransactionId(entity.getGatewayTransactionId()),
                authorizationCode(entity.getAuthorizationCode()),
                entity.getFailureReason(),
                entity.getFailureDetails(),
                entity.getInitiatedAt(),
                entity.getVersion()
        );
        return Payment.restore(snapshot, clock);
    }

    private static GatewayTransactionId gatewayTransactionId(String value) {
        return value == null ? null : GatewayTransactionId.of(value);
    }

    private static AuthorizationCode authorizationCode(String value) {
        return value == null ? null : AuthorizationCode.of(value);
    }
}
