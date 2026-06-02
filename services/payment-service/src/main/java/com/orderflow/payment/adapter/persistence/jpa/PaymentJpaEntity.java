package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.event.PaymentFailed;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.PaymentMethod;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento JPA da tabela {@code payments} — o estado transacional do
 * agregado {@link com.orderflow.payment.domain.model.Payment}.
 *
 * É um detalhe de infraestrutura: vive na borda e nunca vaza para o domínio.
 * A tradução entidade ↔ agregado acontece em {@link JpaPaymentRepository}
 * via {@link com.orderflow.payment.domain.model.Payment.Snapshot}.
 *
 * A coluna {@code version} é gerida explicitamente pelo agregado (incrementa
 * a cada evento) e não via {@code @Version} do Hibernate: a concorrência
 * otimista é aplicada por UPDATE condicional em {@code expected_version}
 * no repositório, espelhando o controle explícito do contexto Ordering.
 */
@Entity
@Table(name = "payments")
class PaymentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "authorized_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal authorizedAmount;

    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal capturedAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false, length = 32)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "gateway_transaction_id", length = 255)
    private String gatewayTransactionId;

    @Column(name = "authorization_code", length = 64)
    private String authorizationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 64)
    private PaymentFailed.FailureReason failureReason;

    @Column(name = "failure_details", length = 512)
    private String failureDetails;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private Instant initiatedAt;

    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentJpaEntity() {
        // exigido pelo JPA
    }

    PaymentJpaEntity(
            UUID id,
            UUID orderId,
            UUID customerId,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            String currency,
            PaymentMethod method,
            PaymentStatus status,
            String gatewayTransactionId,
            String authorizationCode,
            PaymentFailed.FailureReason failureReason,
            String failureDetails,
            Instant initiatedAt,
            long version
    ) {
        this.id = id;
        this.orderId = orderId;
        this.customerId = customerId;
        this.authorizedAmount = authorizedAmount;
        this.capturedAmount = capturedAmount;
        this.refundedAmount = refundedAmount;
        this.currency = currency;
        this.method = method;
        this.status = status;
        this.gatewayTransactionId = gatewayTransactionId;
        this.authorizationCode = authorizationCode;
        this.failureReason = failureReason;
        this.failureDetails = failureDetails;
        this.initiatedAt = initiatedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getOrderId() {
        return orderId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    BigDecimal getAuthorizedAmount() {
        return authorizedAmount;
    }

    BigDecimal getCapturedAmount() {
        return capturedAmount;
    }

    BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    String getCurrency() {
        return currency;
    }

    PaymentMethod getMethod() {
        return method;
    }

    PaymentStatus getStatus() {
        return status;
    }

    String getGatewayTransactionId() {
        return gatewayTransactionId;
    }

    String getAuthorizationCode() {
        return authorizationCode;
    }

    PaymentFailed.FailureReason getFailureReason() {
        return failureReason;
    }

    String getFailureDetails() {
        return failureDetails;
    }

    Instant getInitiatedAt() {
        return initiatedAt;
    }

    long getVersion() {
        return version;
    }
}
