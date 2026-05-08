package com.orderflow.order.domain.model.valueobject;

import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.UUID;

public record ProductId(UUID value) {

    public ProductId {
        Objects.requireNonNull(value, "ProductId value must not be null");
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }

    public static ProductId of(String value) {
        return new ProductId(UUID.fromString(value));
    }

    @Override
    public @NonNull String toString() {
        return value.toString();
    }
}
