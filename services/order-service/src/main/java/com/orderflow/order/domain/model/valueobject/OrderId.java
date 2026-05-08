package com.orderflow.order.domain.model.valueobject;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.UUID;

public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "OrderId value must not be null");
    }

    public static OrderId generate() {
        return new OrderId(UUID.randomUUID());
    }

    public static OrderId of(UUID value) {
        return new OrderId(value);
    }

    @Override
    public @NonNull String toString() {
        return value.toString();
    }
}
