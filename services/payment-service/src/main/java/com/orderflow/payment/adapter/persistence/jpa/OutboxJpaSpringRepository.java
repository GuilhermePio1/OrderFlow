package com.orderflow.payment.adapter.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA sobre {@link OutboxJpaEntity}. Apenas INSERTs acontecem
 * por aqui no caminho de escrita; a leitura/remoção das linhas é feita
 * externamente pelo Debezium (CDC).
 */
interface OutboxJpaSpringRepository extends JpaRepository<OutboxJpaEntity, UUID> {
}
