package com.orderflow.payment.domain.model.valueobject;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador do pedido no contexto Ordering. Aqui é apenas uma referência
 * por identidade: este contexto não conhece o agregado Order, apenas o ID
 * que vem nos eventos consumidos.
 */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "OrderId value must not be null");
    }

    public static OrderId of(UUID value) {
        return new OrderId(value);
    }

    public static OrderId of(String value) {
        return new OrderId(UUID.fromString(value));
    }

    @Override
    public @NonNull String toString() {
        return value.toString();
    }
}
