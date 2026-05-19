package com.orderflow.order.application.command;

import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.model.valueobject.TrackingNumber;

import java.util.Objects;

public record ShipOrderCommand(OrderId orderId, TrackingNumber trackingNumber, String carrier) {

    public ShipOrderCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(trackingNumber, "trackingNumber");
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier must not be blank");
        }
    }
}
