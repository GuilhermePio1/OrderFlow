package com.orderflow.order.adapter.persistence.r2dbc;

import java.io.Serial;

/**
 * Falha ao serializar/desserializar um OrderEvent no event store.
 * Encapsula erros de Jackson para que o caller não dependa da
 * biblioteca de serialização concreta.
 */
public final class EventSerializationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EventSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
