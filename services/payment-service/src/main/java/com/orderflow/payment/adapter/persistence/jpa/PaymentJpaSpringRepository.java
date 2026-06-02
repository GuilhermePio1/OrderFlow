package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.event.PaymentFailed;
import com.orderflow.payment.domain.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA sobre {@link PaymentJpaEntity}. É um detalhe do adapter,
 * envolvido por {@link JpaPaymentRepository} que implementa o port de domínio.
 */
interface PaymentJpaSpringRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByOrderId(UUID orderId);

    /**
     * UPDATE condicional que aplica o controle de concorrência otimista: só
     * altera a linha se a versão persistida ainda for {@code expectedVersion}.
     * Retorna o número de linhas afetadas — zero indica conflito (outra
     * transação avançou a versão).
     *
     * {@code flushAutomatically}/{@code clearAutomatically} garantem que a
     * escrita seja visível e que o contexto de persistência não sirva uma
     * versão obsoleta em leituras subsequentes na mesma transação.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE PaymentJpaEntity p
                SET p.status = :status,
                    p.gatewayTransactionId = :gatewayTransactionId,
                    p.authorizationCode = :authorizationCode,
                    p.capturedAmount = :capturedAmount,
                    p.refundedAmount = :refundedAmount,
                    p.failureReason = :failureReason,
                    p.failureDetails = :failureDetails,
                    p.version = :newVersion
                WHERE p.id = :id
                    AND p.version = :expectedVersion
            """)
    int updateState(
            @Param("id") UUID id,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("status") PaymentStatus status,
            @Param("gatewayTransactionId") String gatewayTransactionId,
            @Param("authorizationCode") String authorizationCode,
            @Param("capturedAmount") BigDecimal capturedAmount,
            @Param("refundedAmount") BigDecimal refundedAmount,
            @Param("failureReason") PaymentFailed.FailureReason failureReason,
            @Param("failureDetails") String failureDetails
    );
}
