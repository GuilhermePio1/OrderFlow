package com.orderflow.order.application.command;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.util.Objects;
import java.util.UUID;

public record ConfirmOrderPaymentCommand(OrderId orderId, UUID paymentId) {

    public ConfirmOrderPaymentCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(paymentId, "paymentId");
    }
}
