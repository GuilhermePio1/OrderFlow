package com.orderflow.order.application.command;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.util.Objects;
import java.util.UUID;

public record ReserveOrderInventoryCommand(OrderId orderId, UUID reservationId) {

    public ReserveOrderInventoryCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reservationId, "reservationId");
    }
}
