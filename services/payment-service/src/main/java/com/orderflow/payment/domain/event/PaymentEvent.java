package com.orderflow.payment.domain.event;

import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.time.Instant;
import java.util.UUID;

/**
 * Fato imutável ocorrido no agregado Payment. Persistido na tabela
 * outbox dentro da mesma transação que altera o estado do pagamento,
 * e propagado para o Kafka via Debezium/CDC.
 */
public sealed interface PaymentEvent
    permits PaymentAuthorized,
            PaymentFailed,
            PaymentCaptured,
            PaymentRefunded,
            PaymentVoided {

    UUID eventId();

    PaymentId paymentId();

    OrderId orderId();

    Instant occurredAt();

    /**
     * Versão do schema do payload. Mudanças aditivas mantêm a versão;
     * mudanças semânticas produzem novo evento.
     */
    int schemaVersion();
}
