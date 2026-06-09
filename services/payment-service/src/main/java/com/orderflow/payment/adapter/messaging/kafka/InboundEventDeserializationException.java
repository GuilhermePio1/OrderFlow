package com.orderflow.payment.adapter.messaging.kafka;

import java.io.Serial;

/**
 * Falha ao desserializar o payload JSON de um evento de entrada vindo do
 * Kafka. Encapsula erros do Jackson para que o caller não dependa da
 * biblioteca concreta de serialização. Uma mensagem cujo payload não
 * desserializa é um poison pill: após as retentativas configuradas, o binder
 * a encaminha para a DLQ ({@code docs/architecture.md}).
 */
public final class InboundEventDeserializationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InboundEventDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
