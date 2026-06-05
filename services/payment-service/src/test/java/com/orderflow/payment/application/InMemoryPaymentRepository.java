package com.orderflow.payment.application;

import com.orderflow.payment.domain.event.PaymentEvent;
import com.orderflow.payment.domain.exception.ConcurrencyConflictException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;
import com.orderflow.payment.domain.repository.PaymentRepository;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake in-memory do {@link PaymentRepository} para testes de caso de uso.
 * Modela o estado por agregado como um {@link Payment.Snapshot} (o contexto
 * Payment é transacional, não event-sourced) mais a concorrência otimista,
 * sem depender de Postgres/JPA. Os eventos publicados são retidos para
 * inspeção, espelhando a tabela outbox.
 */
public final class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<PaymentId, Payment.Snapshot> snapshots = new HashMap<>();
    private final Map<OrderId, PaymentId> byOrder = new HashMap<>();
    private final Map<PaymentId, List<PaymentEvent>> outbox = new HashMap<>();
    private final Clock clock;
    private final AtomicInteger conflictsToInject = new AtomicInteger();
    private final AtomicInteger saveCount = new AtomicInteger();

    public InMemoryPaymentRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Payment> findById(PaymentId paymentId) {
        return Optional.ofNullable(snapshots.get(paymentId))
                .map(snapshot -> Payment.restore(snapshot, clock));
    }

    @Override
    public Optional<Payment> findByOrderId(OrderId orderId) {
        return Optional.ofNullable(byOrder.get(orderId)).flatMap(this::findById);
    }

    @Override
    public void save(Payment payment, long expectedVersion) {
        saveCount.incrementAndGet();
        if (conflictsToInject.getAndUpdate(c -> Math.max(0, c - 1)) > 0) {
            throw new ConcurrencyConflictException(payment.id(), expectedVersion, expectedVersion + 1);
        }
        long storedVersion = Optional.ofNullable(snapshots.get(payment.id()))
                .map(Payment.Snapshot::version)
                .orElse(0L);
        if (storedVersion != expectedVersion) {
            throw new ConcurrencyConflictException(payment.id(), expectedVersion, storedVersion);
        }
        store(payment);
    }

    /** Semeia um pagamento já existente, como se persistido por uma execução anterior. */
    public void seed(Payment payment) {
        store(payment);
    }

    private void store(Payment payment) {
        snapshots.put(payment.id(), snapshotOf(payment));
        byOrder.put(payment.orderId(), payment.id());
        outbox.computeIfAbsent(payment.id(), k -> new ArrayList<>())
                .addAll(payment.pullUncommittedEvents());
    }

    /** Eventos publicados (outbox) para o pagamento, na ordem em que foram gravados. */
    public List<PaymentEvent> events(PaymentId paymentId) {
        return Collections.unmodifiableList(outbox.getOrDefault(paymentId, List.of()));
    }

    public int saveCount() {
        return saveCount.get();
    }

    public void injectConcurrencyConflicts(int n) {
        conflictsToInject.set(n);
    }

    private static Payment.Snapshot snapshotOf(Payment p) {
        return new Payment.Snapshot(
                p.id(),
                p.orderId(),
                p.customerId(),
                p.authorizedAmount(),
                p.capturedAmount(),
                p.refundedAmount(),
                p.method(),
                p.status(),
                p.gatewayTransactionId(),
                p.authorizationCode(),
                p.failureReason(),
                p.failureDetails(),
                p.initiatedAt(),
                p.version()
        );
    }
}
