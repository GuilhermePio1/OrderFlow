package com.orderflow.payment.adapter.persistence.jpa;

import java.io.Serial;

/**
 * O {@link com.orderflow.payment.domain.event.PaymentEvent} não tem
 * mapeamento conhecido no codec. Indica que um novo tipo de evento foi
 * introduzido no domínio sem registro correspondente para publicação.
 */
public final class UnknownEventTypeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnknownEventTypeException(String eventClassName) {
        super("Unknown PaymentEvent type, no outbox mapping registered: " + eventClassName);
    }
}
