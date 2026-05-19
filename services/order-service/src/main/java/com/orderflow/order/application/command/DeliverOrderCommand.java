package com.orderflow.order.application.command;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.util.Objects;

public record DeliverOrderCommand(OrderId orderId) {

    public DeliverOrderCommand {
        Objects.requireNonNull(orderId, "orderId");
    }
}
