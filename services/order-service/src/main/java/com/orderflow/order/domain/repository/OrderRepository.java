package com.orderflow.order.domain.repository;

import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.valueobject.OrderId;
import reactor.core.publisher.Mono;

/**
 * Port de persistência do agregado Order. A implementação vive na camada
 * de infraestrutura e opera em termos de eventos: {@link #save(Order, long)}
 * persiste os eventos pendentes (event store + outbox numa unica transação);
 * {@link #findById(OrderId)} reidrata o agregado a partir do stream de eventos
 * (eventualmente acelerado por snapshot).
 *
 * O parâmetro expectedVersion na escrita implementa concorrência otimista:
 * se a versão no event store divergir, a operação falha com
 * {@link com.orderflow.order.domain.exception.ConcurrencyConflictException}
 * e cabe ao caller reidratar e retentar.
 */
public interface OrderRepository {

    Mono<Order> findById(OrderId orderId);

    /**
     * Persiste os eventos pendentes do agregado.
     *
     * @param order             agregado com eventos não-comprometidos
     * @param expectedVersion   versão do agregado antes dos novos eventos
     *                          (zero para criação); base da concorrência otimista
     * @return Mono que completa quando a transação foi commitada
     */
    Mono<Void> save(Order order, long expectedVersion);
}
