package com.orderflow.payment.domain.model.valueobject;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador do cliente no contexto Payment. Atua apenas como uma referência
 * por identidade para associar a transação ao cliente correto, sem acoplar
 * o contexto de pagamentos aos detalhes do agregado Customer.
 */
public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "CustomerId value must not be null");
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }

    public static CustomerId of(String value) {
        return new CustomerId(UUID.fromString(value));
    }

    @Override
    public @NonNull String toString() {
        return value.toString();
    }
}
