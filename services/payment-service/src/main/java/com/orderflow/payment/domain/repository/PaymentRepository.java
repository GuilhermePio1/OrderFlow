package com.orderflow.payment.domain.repository;

import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.util.Optional;

/**
 * Port de persistência do agregado Payment. A implementação vive na camada
 * de infraestrutura (JPA) e persiste o estado atual + eventos pendentes
 * na tabela outbox numa única transação local.
 *
 * Bloqueante por escolha — o contexto Payment usa Spring MVC com virtual
 * threads. Clareza transacional supera o ganho marginal de programação
 * reativa neste contexto (ver docs/architecture.md).
 *
 * O parâmetro {@code expectedVersion} em {@link #save(Payment, long)}
 * implementa concorrência otimista: se a versão divergir da persistida,
 * falha com {@link com.orderflow.payment.domain.exception.ConcurrencyConflictException}
 * e cabe ao caller recarregar e retentar.
 */
public interface PaymentRepository {

    Optional<Payment> findById(PaymentId paymentId);

    /**
     * Busca pelo ID do pedido. Usado pelo consumer de OrderPlaced para
     * idempotência: se já existe um Payment para o pedido, não criar outro.
     */
    Optional<Payment> findByOrderId(OrderId orderId);

    /**
     * Persiste estado + eventos pendentes do agregado.
     *
     * @param payment         agregado com eventos não-comprometidos
     * @param expectedVersion versão antes dos novos eventos (zero para criação)
     */
    void save(Payment payment, long expectedVersion);
}
