package com.orderflow.payment.adapter.persistence.jpa;

import java.io.Serial;

/**
 * Falha ao serializar um {@link com.orderflow.payment.domain.event.PaymentEvent}
 * para o payload JSON gravado na outbox. Encapsula erros de Jackson para que
 * o caller não dependa da biblioteca de serialização concreta.
 */
public final class EventSerializationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EventSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
