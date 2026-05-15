package com.orderflow.order.application.command;

import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.model.valueobject.OrderId;

import java.util.Objects;

public record CancelOrderCommand(
        OrderId orderId,
        OrderCancelled.CancellationReason reason,
        String details
) {

    public CancelOrderCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
    }
}
