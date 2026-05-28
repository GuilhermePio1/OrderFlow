package com.orderflow.payment.domain.model.valueobject;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Identificador opaco da transação no gateway externo (ex.: Stripe charge id).
 * Não é interpretado dentro do domínio — apenas armazenado para
 * rastreabilidade e operações subsequentes (captura, estorno).
 */
public record GatewayTransactionId(String value) {

    public GatewayTransactionId {
        Objects.requireNonNull(value, "GatewayTransactionId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("GatewayTransactionId value must not be blank");
        }
    }

    public static GatewayTransactionId of(String value) {
        return new GatewayTransactionId(value);
    }

    @Override
    public @NonNull String toString() {
        return value;
    }
}
