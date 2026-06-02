package com.orderflow.payment.adapter.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento JPA da tabela {@code outbox}. Cada linha é um evento de domínio
 * a ser publicado no Kafka pelo Debezium (CDC). O INSERT acontece na mesma
 * transação que altera {@code payments}, garantindo a atomicidade do padrão
 * Outbox (ver {@code docs/event-sourcing.md}).
 *
 * Os nomes de coluna espelham o esperado pelo {@code EventRouter} do
 * Debezium: {@code aggregate_id}, {@code aggregate_type}, {@code event_type},
 * {@code event_id} e {@code payload}.
 */
@Entity
@Table(name = "outbox")
class OutboxJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false, length = 128)
    private String eventType;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, updatable = false)
    private String headers;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxJpaEntity() {
        // exigido pelo JPA
    }

    OutboxJpaEntity(
            UUID id,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            UUID eventId,
            String payload,
            String headers,
            Instant createdAt
    ) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.eventId = eventId;
        this.payload = payload;
        this.headers = headers;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    UUID getAggregateId() {
        return aggregateId;
    }

    String getAggregateType() {
        return aggregateType;
    }

    String getEventType() {
        return eventType;
    }

    UUID getEventId() {
        return eventId;
    }

    String getPayload() {
        return payload;
    }

    String getHeaders() {
        return headers;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
