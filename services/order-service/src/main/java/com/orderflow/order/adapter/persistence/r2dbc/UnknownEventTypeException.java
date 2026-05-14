package com.orderflow.order.adapter.persistence.r2dbc;

import java.io.Serial;

/**
 * O event_type lido do event store não tem mapeamento conhecido no
 * codec. Em produção isso indica que um evento foi persistido por uma
 * versão posterior do serviço e a versão corrente ainda não conhece o
 * tipo — cenário tratado em rollouts coordenados.
 */
public final class UnknownEventTypeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnknownEventTypeException(String eventType) {
        super("Unknown OrderEvent type in event store: " + eventType);
    }
}
