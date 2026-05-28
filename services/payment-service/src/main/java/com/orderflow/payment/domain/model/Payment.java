package com.orderflow.payment.domain.model;

import com.orderflow.payment.domain.event.*;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.exception.RefundExceedsCapturedAmountException;
import com.orderflow.payment.domain.model.valueobject.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Aggregate root do contexto Payment. Modelo transacional clássico — o estado
 * é persistido diretamente (não event-sourced); eventos são publicados via
 * Outbox para os contextos a jusante reagirem.
 *
 * Encapsula autorização, captura e estornos parciais junto a um gateway
 * externo (Stripe/PagSeguro). A comunicação com o gateway acontece em uma
 * Anti-Corruption Layer da camada de aplicação; o agregado conhece apenas
 * o resultado traduzido para seu vocabulário interno.
 *
 * Invariantes:
 *  - amount autorizado positivo e em moeda fixa;
 *  - capturedAmount não pode exceder authorizedAmount;
 *  - refundedAmount não pode exceder capturedAmount;
 *  - transições obedecem à máquina explicita em {@link PaymentStatus};
 *  - comandos repetidos com o mesmo intent são idempotentes.
 */
public final class Payment {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final PaymentId id;
    private final OrderId orderId;
    private final CustomerId customerId;
    private final Money authorizedAmount;
    private final PaymentMethod method;
    private final Instant initiatedAt;
    private final Clock clock;

    private PaymentStatus status;
    private GatewayTransactionId gatewayTransactionId;
    private AuthorizationCode authorizationCode;
    private Money capturedAmount;
    private Money refundedAmount;
    private PaymentFailed.FailureReason failureReason;
    private String failureDetails;
    private long version;

    private final List<PaymentEvent> uncommittedEvents = new ArrayList<>();

    private Payment(
            PaymentId id,
            OrderId orderId,
            CustomerId customerId,
            Money authorizedAmount,
            PaymentMethod method,
            Instant initiatedAt,
            Clock clock
    ) {
        this.id = id;
        this.orderId = orderId;
        this.customerId = customerId;
        this.authorizedAmount = authorizedAmount;
        this.method = method;
        this.initiatedAt = initiatedAt;
        this.clock = clock;
        this.status = PaymentStatus.PENDING;
        this.capturedAmount = Money.zero(authorizedAmount.currency());
        this.refundedAmount = Money.zero(authorizedAmount.currency());
        this.version = 0L;
    }

    // ---------- factories ----------

    /**
     * Inicia um novo pagamento em estado PENDING. Não emite eventos: a
     * iniciação corresponde apenas à criação da linha de transação no banco;
     * apenas o resultado (autorização ou falha) é interessante aos outros
     * contextos.
     */
    public static Payment initiate(
            PaymentId paymentId,
            OrderId orderId,
            CustomerId customerId,
            Money amount,
            PaymentMethod method,
            Clock clock
    ) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(clock, "clock");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Payment amount must be positive, got: " + amount.amount());
        }
        return new Payment(paymentId, orderId, customerId, amount, method, Instant.now(clock), clock);
    }

    /**
     * Reidrata o agregado a partir do estado persistido. Não emite eventos.
     * Usado pelo adapter JPA ao carregar do banco.
     */
    public static Payment restore(Snapshot snapshot, Clock clock) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(clock, "clock");
        Payment p = new Payment(
                snapshot.id(),
                snapshot.orderId(),
                snapshot.customerId(),
                snapshot.authorizedAmount(),
                snapshot.method(),
                snapshot.initiatedAt(),
                clock
        );
        p.status = snapshot.status();
        p.gatewayTransactionId = snapshot.gatewayTransactionId();
        p.authorizationCode = snapshot.authorizationCode();
        p.capturedAmount = snapshot.capturedAmount();
        p.refundedAmount = snapshot.refundedAmount();
        p.failureReason = snapshot.failureReason();
        p.failureDetails = snapshot.failureDetails();
        p.version = snapshot.version();
        return p;
    }

    /**
     * Snapshot do estado persistido. Usado para reidratação a partir
     * do adapter JPA, sem expor os setters internos do agregado.
     */
    public record Snapshot(
            PaymentId id,
            OrderId orderId,
            CustomerId customerId,
            Money authorizedAmount,
            Money capturedAmount,
            Money refundedAmount,
            PaymentMethod method,
            PaymentStatus status,
            GatewayTransactionId gatewayTransactionId,
            AuthorizationCode authorizationCode,
            PaymentFailed.FailureReason failureReason,
            String failureDetails,
            Instant initiatedAt,
            long version
    ) {
        public Snapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(authorizedAmount, "authorizedAmount");
            Objects.requireNonNull(capturedAmount, "capturedAmount");
            Objects.requireNonNull(refundedAmount, "refundedAmount");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(initiatedAt, "initiatedAt");
        }
    }

    // ---------- commands ----------

    public void authorize(GatewayTransactionId gatewayTransactionId, AuthorizationCode authorizationCode) {
        Objects.requireNonNull(gatewayTransactionId, "gatewayTransactionId");
        Objects.requireNonNull(authorizationCode, "authorizationCode");
        if (status == PaymentStatus.AUTHORIZED || status == PaymentStatus.CAPTURED) {
            return; // idempotente
        }
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateTransitionException(status, "authorize");
        }
        this.status = PaymentStatus.AUTHORIZED;
        this.gatewayTransactionId = gatewayTransactionId;
        this.authorizationCode = authorizationCode;
        record(new PaymentAuthorized(
                UUID.randomUUID(),
                id,
                orderId,
                customerId,
                authorizedAmount,
                method,
                gatewayTransactionId,
                authorizationCode,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    public void fail(PaymentFailed.FailureReason reason, String details) {
        Objects.requireNonNull(reason, "reason");
        if (status == PaymentStatus.FAILED) {
            return; // idempotente
        }
        if (status != PaymentStatus.PENDING) {
            throw new InvalidPaymentStateTransitionException(status, "fail");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.failureDetails = details;
        record(new PaymentFailed(
                UUID.randomUUID(),
                id,
                orderId,
                customerId,
                authorizedAmount,
                reason,
                details,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    public void capture() {
        if (status == PaymentStatus.CAPTURED) {
            return; // idempotente
        }
        if (status != PaymentStatus.AUTHORIZED) {
            throw new InvalidPaymentStateTransitionException(status, "capture");
        }
        this.status = PaymentStatus.CAPTURED;
        this.capturedAmount = authorizedAmount;
        record(new PaymentCaptured(
                UUID.randomUUID(),
                id,
                orderId,
                authorizedAmount,
                gatewayTransactionId,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    /**
     * Estorno parcial ou total. Múltiplas chamadas são permitidas enquanto
     * o total estornado for menor que o capturado; quando atingem o valor
     * capturado, o pagamento transiciona para REFUNDED (terminal).
     */
    public void refund(Money amount, String reason) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Refund amount must be positive, got: " + amount.amount());
        }
        if (status != PaymentStatus.CAPTURED) {
            throw new InvalidPaymentStateTransitionException(status, "refund");
        }
        Money remaining = capturedAmount.minus(refundedAmount);
        if (amount.isGreaterThan(remaining)) {
            throw new RefundExceedsCapturedAmountException(amount, remaining);
        }
        Money newTotal = refundedAmount.plus(amount);
        boolean fullRefund = newTotal.isEqualValue(capturedAmount);
        this.refundedAmount = newTotal;
        if (fullRefund) {
            this.status = PaymentStatus.REFUNDED;
        }
        record(new PaymentRefunded(
                UUID.randomUUID(),
                id,
                orderId,
                amount,
                newTotal,
                fullRefund,
                reason,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    /**
     * Reverte uma autorização ainda não capturada. Usado pela saga de
     * compensação quando o pagamento foi autorizado mas o estoque está
     * esgotado.
     */
    public void voidAuthorization(String reason) {
        if (status == PaymentStatus.VOIDED) {
            return; // idempotente
        }
        if (status != PaymentStatus.AUTHORIZED) {
            throw new InvalidPaymentStateTransitionException(status, "voidAuthorization");
        }
        this.status = PaymentStatus.VOIDED;
        record(new PaymentVoided(
                UUID.randomUUID(),
                id,
                orderId,
                reason,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    // ---------- event plumbing ----------

    private void record(PaymentEvent event) {
        uncommittedEvents.add(event);
        version++;
    }

    /**
     * Retorna e limpa os eventos pendentes. Chamado pelo repositório
     * após persistir o estado + outbox numa mesma transação.
     */
    public List<PaymentEvent> pullUncommittedEvents() {
        List<PaymentEvent> snapshot = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return snapshot;
    }

    public boolean hasUncommittedEvents() {
        return !uncommittedEvents.isEmpty();
    }

    // ---------- queries ----------

    public PaymentId id() {
        return id;
    }

    public OrderId orderId() {
        return orderId;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public Money authorizedAmount() {
        return authorizedAmount;
    }

    public Money capturedAmount() {
        return capturedAmount;
    }

    public Money refundedAmount() {
        return refundedAmount;
    }

    public Money remainingCapturedAmount() {
        return capturedAmount.minus(refundedAmount);
    }

    public PaymentMethod method() {
        return method;
    }

    public PaymentStatus status() {
        return status;
    }

    public GatewayTransactionId gatewayTransactionId() {
        return gatewayTransactionId;
    }

    public AuthorizationCode authorizationCode() {
        return authorizationCode;
    }

    public PaymentFailed.FailureReason failureReason() {
        return failureReason;
    }

    public String failureDetails() {
        return failureDetails;
    }

    public Instant initiatedAt() {
        return initiatedAt;
    }

    /**
     * Versão para concorrência otimista. Incrementa a cada evento registrado.
     */
    public long version() {
        return version;
    }

    public List<PaymentEvent> peekUncommittedEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }
}
