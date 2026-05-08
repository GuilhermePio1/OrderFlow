package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.time.Instant;
import java.util.UUID;

/**
 * Fato imutável ocorrido no agregado Order. Persistido no event store
 * e propagado via Outbox/Kafka para os contextos a jusante.
 */
public sealed interface OrderEvent
        permits OrderPlaced,
                OrderPaymentConfirmed,
                OrderInventoryReserved,
                OrderConfirmed,
                OrderShipped,
                OrderDelivered,
                OrderCancelled {

    UUID eventId();

    OrderId orderId();

    Instant occurredAt();

    /**
     * Versão do schema do payload, usada por upcasters durante a leitura.
     * Mudanças aditivas mantém a versão; mudanças semânticas produzem novo evento.
     */
    int schemaVersion();
}
