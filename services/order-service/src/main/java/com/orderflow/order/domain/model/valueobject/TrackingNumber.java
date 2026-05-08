package com.orderflow.order.domain.model.valueobject;

public record TrackingNumber(String value) {
    public TrackingNumber {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tracking number must not be blank");
        }
        // Futuras validações de regex para transportadoras podem entrar aqui
    }

    public static TrackingNumber of(String value) {
        return new TrackingNumber(value);
    }
}
